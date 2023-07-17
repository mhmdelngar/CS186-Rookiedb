package edu.berkeley.cs186.database.recovery;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import edu.berkeley.cs186.database.Transaction;
import edu.berkeley.cs186.database.common.Pair;
import edu.berkeley.cs186.database.concurrency.DummyLockContext;
import edu.berkeley.cs186.database.io.DiskSpaceManager;
import edu.berkeley.cs186.database.memory.BufferManager;
import edu.berkeley.cs186.database.memory.Page;
import edu.berkeley.cs186.database.recovery.records.AbortTransactionLogRecord;
import edu.berkeley.cs186.database.recovery.records.AllocPageLogRecord;
import edu.berkeley.cs186.database.recovery.records.AllocPartLogRecord;
import edu.berkeley.cs186.database.recovery.records.BeginCheckpointLogRecord;
import edu.berkeley.cs186.database.recovery.records.CommitTransactionLogRecord;
import edu.berkeley.cs186.database.recovery.records.EndCheckpointLogRecord;
import edu.berkeley.cs186.database.recovery.records.EndTransactionLogRecord;
import edu.berkeley.cs186.database.recovery.records.FreePageLogRecord;
import edu.berkeley.cs186.database.recovery.records.FreePartLogRecord;
import edu.berkeley.cs186.database.recovery.records.MasterLogRecord;
import edu.berkeley.cs186.database.recovery.records.UpdatePageLogRecord;

/**
 * Implementation of ARIES.
 */
public class ARIESRecoveryManager implements RecoveryManager {
    // Disk space manager.
    DiskSpaceManager diskSpaceManager;
    // Buffer manager.
    BufferManager bufferManager;

    // Function to create a new transaction for recovery with a given
    // transaction number.
    private Function<Long, Transaction> newTransaction;

    // Log manager
    LogManager logManager;
    // Dirty page table (page number -> recLSN).
    Map<Long, Long> dirtyPageTable = new ConcurrentHashMap<>();
    // Transaction table (transaction number -> entry).
    Map<Long, TransactionTableEntry> transactionTable = new ConcurrentHashMap<>();
    // true if redo phase of restart has terminated, false otherwise. Used
    // to prevent DPT entries from being flushed during restartRedo.
    boolean redoComplete;

    public ARIESRecoveryManager(Function<Long, Transaction> newTransaction) {
        this.newTransaction = newTransaction;
    }

    /**
     * Initializes the log; only called the first time the database is set up.
     * The master record should be added to the log, and a checkpoint should be
     * taken.
     */
    @Override
    public void initialize() {
        this.logManager.appendToLog(new MasterLogRecord(0));
        this.checkpoint();
    }

    /**
     * Sets the buffer/disk managers. This is not part of the constructor
     * because of the cyclic dependency between the buffer manager and recovery
     * manager (the buffer manager must interface with the recovery manager to
     * block page evictions until the log has been flushed, but the recovery
     * manager needs to interface with the buffer manager to write the log and
     * redo changes).
     *
     * @param diskSpaceManager disk space manager
     * @param bufferManager    buffer manager
     */
    @Override
    public void setManagers(DiskSpaceManager diskSpaceManager, BufferManager bufferManager) {
        this.diskSpaceManager = diskSpaceManager;
        this.bufferManager = bufferManager;
        this.logManager = new LogManager(bufferManager);
    }

    // Forward Processing //////////////////////////////////////////////////////

    /**
     * Called when a new transaction is started.
     * <p>
     * The transaction should be added to the transaction table.
     *
     * @param transaction new transaction
     */
    @Override
    public synchronized void startTransaction(Transaction transaction) {
        this.transactionTable.put(transaction.getTransNum(), new TransactionTableEntry(transaction));
    }

    /**
     * Called when a transaction is about to start committing.
     * <p>
     * A commit record should be appended, the log should be flushed,
     * and the transaction table and the transaction status should be updated.
     *
     * @param transNum trans action being committed
     * @return LSN of the commit record
     */
    @Override
    public long commit(long transNum) {
        // TODO(proj5): implement
        long Lsn = logManager.appendToLog(new CommitTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN));
        logManager.flushToLSN(Lsn);
        transactionTable.get(transNum).lastLSN = Lsn;
        transactionTable.get(transNum).transaction.setStatus(Transaction.Status.COMMITTING);


        return Lsn;
    }

    /**
     * Called when a transaction is set to be aborted.
     * <p>
     * An abort record should be appended, and the transaction table and
     * transaction status should be updated. Calling this function should not
     * perform any rollbacks.
     *
     * @param transNum transaction being aborted
     * @return LSN of the abort record
     */
    @Override
    public long abort(long transNum) {
        // TODO(proj5): implement

        long Lsn = logManager.appendToLog(new AbortTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN));
        transactionTable.get(transNum).lastLSN = Lsn;
        transactionTable.get(transNum).transaction.setStatus(Transaction.Status.ABORTING);

        return Lsn;
    }

    /**
     * Called when a transaction is cleaning up; this should roll back
     * changes if the transaction is aborting (see the rollbackToLSN helper
     * function below).
     * <p>
     * Any changes that need to be undone should be undone, the transaction should
     * be removed from the transaction table, the end record should be appended,
     * and the transaction status should be updated.
     *
     * @param transNum transaction to end
     * @return LSN of the end record
     */
    @Override
    public long end(long transNum) {
        // TODO(proj5): implement
        if (transactionTable.get(transNum).transaction.getStatus() == Transaction.Status.ABORTING) {
            LogRecord logRecord = logManager.fetchLogRecord(transactionTable.get(transNum).lastLSN);
//

            rollbackToLSN(transNum, 0);

        }
        long LSN = logManager.appendToLog(new EndTransactionLogRecord(transNum, transactionTable.get(transNum).lastLSN));
        transactionTable.get(transNum).transaction.setStatus(Transaction.Status.COMPLETE);
        transactionTable.remove(transNum);
        return LSN;
    }

    /**
     * Recommended helper function: performs a rollback of all of a
     * transaction's actions, up to (but not including) a certain LSN.
     * Starting with the LSN of the most recent record that hasn't been undone:
     * - while the current LSN is greater than the LSN we're rolling back to:
     * - if the record at the current LSN is undoable:
     * - Get a compensation log record (CLR) by calling undo on the record
     * - Emit the CLR
     * - Call redo on the CLR to perform the undo
     * - update the current LSN to that of the next record to undo
     * <p>
     * Note above that calling .undo() on a record does not perform the undo, it
     * just creates the compensation log record.
     *
     * @param transNum transaction to perform a rollback for
     * @param LSN      LSN to which we should rollback
     */
    private void rollbackToLSN(long transNum, long LSN) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        LogRecord lastRecord = logManager.fetchLogRecord(transactionEntry.lastLSN);
        long lastRecordLSN = lastRecord.getLSN();
        // Small optimization: if the last record is a CLR we can start rolling
        // back from the next record that hasn't yet been undone.
        long currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecordLSN);
        System.out.println(currentLSN);
        while (currentLSN > LSN) {
            lastRecord = logManager.fetchLogRecord(currentLSN);
            if (lastRecord.isUndoable()) {

                LogRecord clrRecord = lastRecord.undo(transactionTable.get(transNum).lastLSN);

                long lsn=logManager.appendToLog(clrRecord);
                transactionTable.get(transNum).lastLSN=lsn;
                clrRecord.redo(this, diskSpaceManager, bufferManager);
            }

            if (lastRecord.getPrevLSN().isPresent()) {
                currentLSN = lastRecord.getUndoNextLSN().orElse(lastRecord.getPrevLSN().get());
                System.out.println(currentLSN);
            } else {
                break;
            }

        }
        // TODO(proj5) implement the rollback logic described above
    }

    /**
     * Called before a page is flushed from the buffer cache. This
     * method is never called on a log page.
     * <p>
     * The log should be as far as necessary.
     *
     * @param pageLSN pageLSN of page about to be flushed
     */
    @Override
    public void pageFlushHook(long pageLSN) {
        logManager.flushToLSN(pageLSN);
    }

    /**
     * Called when a page has been updated on disk.
     * <p>
     * As the page is no longer dirty, it should be removed from the
     * dirty page table.
     *
     * @param pageNum page number of page updated on disk
     */
    @Override
    public void diskIOHook(long pageNum) {
        if (redoComplete) dirtyPageTable.remove(pageNum);
    }

    /**
     * Called when a write to a page happens.
     * <p>
     * This method is never called on a log page. Arguments to the before and after params
     * are guaranteed to be the same length.
     * <p>
     * The appropriate log record should be appended, and the transaction table
     * and dirty page table should be updated accordingly.
     *
     * @param transNum   transaction performing the write
     * @param pageNum    page number of page being written
     * @param pageOffset offset into page where write begins
     * @param before     bytes starting at pageOffset before the write
     * @param after      bytes starting at pageOffset after the write
     * @return LSN of last record written to log
     */
    @Override
    public long logPageWrite(long transNum, long pageNum, short pageOffset, byte[] before,
                             byte[] after) {
        assert (before.length == after.length);
        assert (before.length <= BufferManager.EFFECTIVE_PAGE_SIZE / 2);
        // TODO(proj5): implement
        long Lsn = logManager.appendToLog(new UpdatePageLogRecord(transNum, pageNum, transactionTable.get(transNum).lastLSN, pageOffset, before, after));
        if (!dirtyPageTable.containsKey(pageNum)) {
            dirtyPageTable.put(pageNum, Lsn);
        }

        transactionTable.get(transNum).lastLSN = Lsn;


        return Lsn;
    }

    /**
     * Called when a new partition is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     * <p>
     * This method should return -1 if the partition is the log partition.
     * <p>
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param partNum  partition number of the new partition
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a partition is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     * <p>
     * This method should return -1 if the partition is the log partition.
     * <p>
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the partition be freed
     * @param partNum  partition number of the partition being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePart(long transNum, int partNum) {
        // Ignore if part of the log.
        if (partNum == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePartLogRecord(transNum, partNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a new page is allocated. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     * <p>
     * This method should return -1 if the page is in the log partition.
     * <p>
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the allocation
     * @param pageNum  page number of the new page
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logAllocPage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new AllocPageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Called when a page is freed. A log flush is necessary,
     * since changes are visible on disk immediately after this returns.
     * <p>
     * This method should return -1 if the page is in the log partition.
     * <p>
     * The appropriate log record should be appended, and the log flushed.
     * The transaction table should be updated accordingly.
     *
     * @param transNum transaction requesting the page be freed
     * @param pageNum  page number of the page being freed
     * @return LSN of record or -1 if log partition
     */
    @Override
    public long logFreePage(long transNum, long pageNum) {
        // Ignore if part of the log.
        if (DiskSpaceManager.getPartNum(pageNum) == 0) return -1L;

        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        long prevLSN = transactionEntry.lastLSN;
        LogRecord record = new FreePageLogRecord(transNum, pageNum, prevLSN);
        long LSN = logManager.appendToLog(record);
        // Update lastLSN
        transactionEntry.lastLSN = LSN;
        dirtyPageTable.remove(pageNum);
        // Flush log
        logManager.flushToLSN(LSN);
        return LSN;
    }

    /**
     * Creates a savepoint for a transaction. Creating a savepoint with
     * the same name as an existing savepoint for the transaction should
     * delete the old savepoint.
     * <p>
     * The appropriate LSN should be recorded so that a partial rollback
     * is possible later.
     *
     * @param transNum transaction to make savepoint for
     * @param name     name of savepoint
     */
    @Override
    public void savepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.addSavepoint(name);
    }

    /**
     * Releases (deletes) a savepoint for a transaction.
     *
     * @param transNum transaction to delete savepoint for
     * @param name     name of savepoint
     */
    @Override
    public void releaseSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);
        transactionEntry.deleteSavepoint(name);
    }

    /**
     * Rolls back transaction to a savepoint.
     * <p>
     * All changes done by the transaction since the savepoint should be undone,
     * in reverse order, with the appropriate CLRs written to log. The transaction
     * status should remain unchanged.
     *
     * @param transNum transaction to partially rollback
     * @param name     name of savepoint
     */
    @Override
    public void rollbackToSavepoint(long transNum, String name) {
        TransactionTableEntry transactionEntry = transactionTable.get(transNum);
        assert (transactionEntry != null);

        // All of the transaction's changes strictly after the record at LSN should be undone.
        long savepointLSN = transactionEntry.getSavepoint(name);
        rollbackToLSN(transNum, savepointLSN);

        // TODO(proj5): implement
        return;
    }

    /**
     * Create a checkpoint.
     * <p>
     * First, a begin checkpoint record should be written.
     * <p>
     * Then, end checkpoint records should be filled up as much as possible first
     * using recLSNs from the DPT, then status/lastLSNs from the transactions
     * table, and written when full (or when nothing is left to be written).
     * You may find the method EndCheckpointLogRecord#fitsInOneRecord here to
     * figure out when to write an end checkpoint record.
     * <p>
     * Finally, the master record should be rewritten with the LSN of the
     * begin checkpoint record.
     */
    @Override
    public synchronized void checkpoint() {
        // Create begin checkpoint log record and write to log
        LogRecord beginRecord = new BeginCheckpointLogRecord();
        long beginLSN = logManager.appendToLog(beginRecord);

        Map<Long, Long> chkptDPT = new HashMap<>();
        Map<Long, Pair<Transaction.Status, Long>> chkptTxnTable = new HashMap<>();

        // TODO(proj5): generate end checkpoint record(s) for DPT and transaction table
        int dPT = 0;
        int Txn = 0;
        for (Long pageNumber :
                dirtyPageTable.keySet()) {
            dPT++;

            if (!EndCheckpointLogRecord.fitsInOneRecord(dPT, Txn)) {
                ;
                LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(endRecord);
                chkptDPT.clear();
                dPT = 1;
                Txn = 0;

            }
            chkptDPT.put(pageNumber, dirtyPageTable.get(pageNumber));


        }

        for (Long transNumber :
                transactionTable.keySet()) {
            Txn++;

            if (!EndCheckpointLogRecord.fitsInOneRecord(dPT, Txn)) {
                LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
                logManager.appendToLog(endRecord);
                chkptTxnTable.clear();
                chkptDPT.clear();
                dPT = 0;
                Txn = 1;

            }
            chkptTxnTable.put(transNumber, new Pair(transactionTable.get(transNumber).transaction.getStatus(), transactionTable.get(transNumber).lastLSN));


        }
        // Last end checkpoint record
        LogRecord endRecord = new EndCheckpointLogRecord(chkptDPT, chkptTxnTable);
        logManager.appendToLog(endRecord);
        // Ensure checkpoint is fully flushed before updating the master record
        flushToLSN(endRecord.getLSN());

        // Update master record
        MasterLogRecord masterRecord = new MasterLogRecord(beginLSN);
        logManager.rewriteMasterRecord(masterRecord);
    }

    /**
     * Flushes the log to at least the specified record,
     * essentially flushing up to and including the page
     * that contains the record specified by the LSN.
     *
     * @param LSN LSN up to which the log should be flushed
     */
    @Override
    public void flushToLSN(long LSN) {
        this.logManager.flushToLSN(LSN);
    }

    @Override
    public void dirtyPage(long pageNum, long LSN) {
        dirtyPageTable.putIfAbsent(pageNum, LSN);
        // Handle race condition where earlier log is beaten to the insertion by
        // a later log.
        dirtyPageTable.computeIfPresent(pageNum, (k, v) -> Math.min(LSN, v));
    }

    @Override
    public void close() {
        this.checkpoint();
        this.logManager.close();
    }

    // Restart Recovery ////////////////////////////////////////////////////////

    /**
     * Called whenever the database starts up, and performs restart recovery.
     * Recovery is complete when the Runnable returned is run to termination.
     * New transactions may be started once this method returns.
     * <p>
     * This should perform the three phases of recovery, and also clean the
     * dirty page table of non-dirty pages (pages that aren't dirty in the
     * buffer manager) between redo and undo, and perform a checkpoint after
     * undo.
     */
    @Override
    public void restart() {
        this.restartAnalysis();
        this.restartRedo();
        this.redoComplete = true;
        this.cleanDPT();
        this.restartUndo();
        this.checkpoint();
    }

    /**
     * This method performs the analysis pass of restart recovery.
     * <p>
     * First, the master record should be read (LSN 0). The master record contains
     * one piece of information: the LSN of the last successful checkpoint.
     * <p>
     * We then begin scanning log records, starting at the beginning of the
     * last successful checkpoint.
     * <p>
     * If the log record is for a transaction operation (getTransNum is present)
     * - update the transaction table
     * <p>
     * If the log record is page-related, update the dpt
     * - update/undoupdate page will dirty pages
     * - free/undoalloc page always flush changes to disk
     * - no action needed for alloc/undofree page
     * <p>
     * If the log record is for a change in transaction status:
     * - if END_TRANSACTION: clean up transaction (Transaction#cleanup), remove
     * from txn table, and add to endedTransactions
     * - update transaction status to COMMITTING/RECOVERY_ABORTING/COMPLETE
     * - update the transaction table
     * <p>
     * If the log record is an end_checkpoint record:
     * - Copy all entries of checkpoint DPT (replace existing entries if any)
     * - Skip txn table entries for transactions that have already ended
     * - Add to transaction table if not already present
     * - Update lastLSN to be the larger of the existing entry's (if any) and
     * the checkpoint's
     * - The status's in the transaction table should be updated if it is possible
     * to transition from the status in the table to the status in the
     * checkpoint. For example, running -> aborting is a possible transition,
     * but aborting -> running is not.
     * <p>
     * After all records are processed, cleanup and end transactions that are in
     * the COMMITING state, and move all transactions in the RUNNING state to
     * RECOVERY_ABORTING/emit an abort record.
     */
    void restartAnalysis() {
        // Read master record
        LogRecord record = logManager.fetchLogRecord(0L);
        // Type checking
        assert (record != null && record.getType() == LogType.MASTER);
        MasterLogRecord masterRecord = (MasterLogRecord) record;
        // Get start checkpoint LSN
        long LSN = masterRecord.lastCheckpointLSN;
        // Set of transactions that have completed
        Set<Long> endedTransactions = new HashSet<>();
        Iterator<LogRecord> scaninng = logManager.scanFrom(LSN);
        while (scaninng.hasNext()) {
            LogRecord logRecord = scaninng.next();
            if (logRecord.getTransNum().isPresent()) {
                Transaction transaction = newTransaction.apply(logRecord.getTransNum().get());
                transactionTable.putIfAbsent(logRecord.getTransNum().get(), new TransactionTableEntry(transaction));
                transactionTable.get(logRecord.getTransNum().get()).lastLSN = logRecord.LSN;


            }
            if (logRecord.type == LogType.UNDO_UPDATE_PAGE || logRecord.type == LogType.UPDATE_PAGE) {
                dirtyPageTable.putIfAbsent(logRecord.getPageNum().get(), logRecord.LSN);

            }
            if (logRecord.type == LogType.UNDO_ALLOC_PAGE || logRecord.type == LogType.FREE_PAGE) {
                dirtyPageTable.remove(logRecord.getPageNum().get());
            }
            if (logRecord.type == LogType.COMMIT_TRANSACTION || logRecord.type == LogType.ABORT_TRANSACTION || logRecord.type == LogType.END_TRANSACTION) {

                Transaction transaction = newTransaction.apply(logRecord.getTransNum().get());
                transactionTable.putIfAbsent(logRecord.getTransNum().get(), new TransactionTableEntry(transaction));
                transactionTable.get(logRecord.getTransNum().get()).lastLSN = logRecord.LSN;
                if (logRecord.type == LogType.ABORT_TRANSACTION) {
                    transactionTable.get(logRecord.getTransNum().get()).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);

                }
                if (logRecord.type == LogType.ABORT_TRANSACTION) {
                    transactionTable.get(logRecord.getTransNum().get()).transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);

                }
                if (logRecord.type == LogType.COMMIT_TRANSACTION) {
                    transactionTable.get(logRecord.getTransNum().get()).transaction.setStatus(Transaction.Status.COMMITTING);

                }
                if (logRecord.type == LogType.END_TRANSACTION) {
                    transaction.cleanup();
                    transactionTable.get(logRecord.getTransNum().get()).transaction.setStatus(Transaction.Status.COMPLETE);
                    transactionTable.remove(logRecord.getTransNum().get());
                    endedTransactions.add(logRecord.getTransNum().get());
                }

            }
            if (logRecord.type == LogType.END_CHECKPOINT) {
                for (Map.Entry<Long, Long> entry : logRecord.getDirtyPageTable().entrySet()) {
                    dirtyPageTable.put(entry.getKey(), entry.getValue());
                }
                for (Map.Entry<Long, Pair<Transaction.Status, Long>> entry : logRecord.getTransactionTable().entrySet()) {
                    if (endedTransactions.contains(entry.getKey())) continue;
                    if (transactionTable.containsKey(entry.getKey())) {
                        if (isMoreAdvanced(transactionTable.get(entry.getKey()).transaction.getStatus(), entry.getValue().getFirst())) {
                            Transaction.Status status = entry.getValue().getFirst();
                            if (entry.getValue().getFirst() == Transaction.Status.ABORTING) {
                                status = Transaction.Status.RECOVERY_ABORTING;

                            }
                            transactionTable.get(entry.getKey()).transaction.setStatus(status);

                        }

                    } else {
                        Transaction transaction = newTransaction.apply(entry.getKey());
                        transactionTable.putIfAbsent(entry.getKey(), new TransactionTableEntry(transaction));
                        Transaction.Status status = entry.getValue().getFirst();
                        if (entry.getValue().getFirst() == Transaction.Status.ABORTING) {
                            status = Transaction.Status.RECOVERY_ABORTING;

                        }
                        transactionTable.get(entry.getKey()).transaction.setStatus(status);
                    }
                    transactionTable.get(entry.getKey()).lastLSN = Math.max(transactionTable.get(entry.getKey()).lastLSN, entry.getValue().getSecond());
                    System.out.println(entry.getValue().getSecond());

                }

//                System.out.println(transactionTable);

            }

        }
        for (Map.Entry<Long, TransactionTableEntry> entry : transactionTable.entrySet()) {
            if (entry.getValue().transaction.getStatus() == Transaction.Status.COMMITTING) {

                entry.getValue().transaction.cleanup();

                entry.getValue().transaction.setStatus(Transaction.Status.COMPLETE);

                long lsn = logManager.appendToLog(new EndTransactionLogRecord(entry.getValue().transaction.getTransNum(), entry.getValue().lastLSN));
                transactionTable.get(entry.getKey()).lastLSN = lsn;
                endedTransactions.add(entry.getKey());

                transactionTable.remove(entry.getKey());
            }
            if (entry.getValue().transaction.getStatus() == Transaction.Status.RUNNING) {
                entry.getValue().transaction.setStatus(Transaction.Status.RECOVERY_ABORTING);
                long lsn = logManager.appendToLog(new AbortTransactionLogRecord(entry.getValue().transaction.getTransNum(), entry.getValue().lastLSN));
                transactionTable.get(entry.getKey()).lastLSN = lsn;
            }
        }
        for (TransactionTableEntry t :
                transactionTable.values()) {
            System.out.println(t.transaction.getStatus());
        }
        System.out.println(endedTransactions);
        // TODO(proj5): implement
        return;
    }

    /**
     * This method performs the redo pass of restart recovery.
     * <p>
     * First, determine the starting point for REDO from the dirty page table.
     * <p>
     * Then, scanning from the starting point, if the record is redoable and
     * - about a partition (Alloc/Free/UndoAlloc/UndoFree..Part), always redo it
     * - allocates a page (AllocPage/UndoFreePage), always redo it
     * - modifies a page (Update/UndoUpdate/Free/UndoAlloc....Page) in
     * the dirty page table with LSN >= recLSN, the page is fetched from disk,
     * the pageLSN is checked, and the record is redone if needed.
     */
    void restartRedo() {
        // TODO(proj5): implement
        long startingLsn = Integer.MAX_VALUE;
        for (Long recLsn :
                dirtyPageTable.values()) {
            if (recLsn < startingLsn) {
                startingLsn = recLsn;
            }

        }
        Iterator<LogRecord> scaninng = logManager.scanFrom(startingLsn);
        final Set<LogType> alwaysTrueValues = Set.of(LogType.ALLOC_PART, LogType.UNDO_ALLOC_PART, LogType.FREE_PART, LogType.UNDO_FREE_PART);
        final Set<LogType> conditionTrueValues = Set.of(LogType.UPDATE_PAGE, LogType.UNDO_UPDATE_PAGE, LogType.UNDO_ALLOC_PAGE, LogType.FREE_PAGE);

        while (scaninng.hasNext()) {
            LogRecord logRecord = scaninng.next();
            if (logRecord.isRedoable()) {

                if (alwaysTrueValues.contains(logRecord.type)) {

                    logRecord.redo(this, diskSpaceManager, bufferManager);
                }
                if (logRecord.getPageNum().isEmpty()) {
                    continue;
                }
                long pageNumber = logRecord.getPageNum().get();
                if (conditionTrueValues.contains(logRecord.type)) {
                    if (dirtyPageTable.containsKey(pageNumber) && logRecord.LSN >= dirtyPageTable.get(pageNumber)) {
                        Page page = bufferManager.fetchPage(new DummyLockContext(), pageNumber);
                        try {
                            // Do anything that requires the page here
                            if (page.getPageLSN() < logRecord.LSN)
                                logRecord.redo(this, diskSpaceManager, bufferManager);

                        } finally {
                            page.unpin();
                        }
                    }

                }
            }

        }
    }

    /**
     * This method performs the undo pass of restart recovery.
     * <p>
     * First, a priority queue is created sorted on lastLSN of all aborting transactions.
     * <p>
     * Then, always working on the largest LSN in the priority queue until we are done,
     * - if the record is undoable, undo it, and emit the appropriate CLR
     * - replace the entry in the set should be replaced with a new one, using the undoNextLSN
     * (or prevLSN if not available) of the record; and
     * - if the new LSN is 0, end the transaction and remove it from the queue and transaction table.
     */
    void restartUndo() {
        // TODO(proj5): implement
        Set<Long> toUndo = new HashSet<Long>();
        for (TransactionTableEntry e :
                transactionTable.values()) {
            toUndo.add(e.lastLSN);

        }
//        System.out.println(toUndo);
        while (!toUndo.isEmpty()) {
            long max = Collections.max(toUndo);
            toUndo.remove(max);
            LogRecord thisLR = logManager.fetchLogRecord(max);
            long L = 0;
            System.out.println(thisLR.type);
            long las=thisLR.getLSN();
            System.out.println(thisLR.getLSN());
            if (thisLR.isUndoable()) {

                System.out.println(thisLR.getPrevLSN().get());
                LogRecord clrRecord = thisLR.undo(transactionTable.get(thisLR.getTransNum().get()).lastLSN);
                Long lastLsn = logManager.appendToLog(clrRecord);
                transactionTable.get(thisLR.getTransNum().get()).lastLSN = lastLsn;
                System.out.println(clrRecord.getPageNum());
                clrRecord.redo(this, diskSpaceManager, bufferManager);

                if (clrRecord.getUndoNextLSN().isPresent()) {
                    L = clrRecord.getUndoNextLSN().get();

                } else {
                    if (clrRecord.getPrevLSN().isPresent()) L = clrRecord.getPrevLSN().get();

                }


            } else {
                if (thisLR.getUndoNextLSN().isPresent()) {
                    L = thisLR.getUndoNextLSN().get();

                } else if (thisLR.getPrevLSN().isPresent()) L = thisLR.getPrevLSN().get();

            }
            if (L == 0) {
                TransactionTableEntry entry = transactionTable.get(thisLR.getTransNum().get());
                entry.transaction.cleanup();
                entry.transaction.setStatus(Transaction.Status.COMPLETE);
                logManager.appendToLog(new EndTransactionLogRecord(thisLR.getTransNum().get(), entry.lastLSN));
                transactionTable.remove(entry.transaction.getTransNum());
            } else {
                toUndo.add(L);
            }


        }

        return;
    }

    /**
     * Removes pages from the DPT that are not dirty in the buffer manager.
     * This is slow and should only be used during recovery.
     */
    void cleanDPT() {
        Set<Long> dirtyPages = new HashSet<>();
        bufferManager.iterPageNums((pageNum, dirty) -> {
            if (dirty) dirtyPages.add(pageNum);
        });
        Map<Long, Long> oldDPT = new HashMap<>(dirtyPageTable);
        dirtyPageTable.clear();
        for (long pageNum : dirtyPages) {
            if (oldDPT.containsKey(pageNum)) {
                dirtyPageTable.put(pageNum, oldDPT.get(pageNum));
            }
        }
    }

    // Helpers /////////////////////////////////////////////////////////////////

    /**
     * Comparator for Pair<A, B> comparing only on the first element (type A),
     * in reverse order.
     */
    private static class PairFirstReverseComparator<A extends Comparable<A>, B> implements
            Comparator<Pair<A, B>> {
        @Override
        public int compare(Pair<A, B> p0, Pair<A, B> p1) {
            return p1.getFirst().compareTo(p0.getFirst());
        }
    }

    boolean isMoreAdvanced(Transaction.Status status1, Transaction.Status status2) {
        if (status1 == Transaction.Status.COMPLETE) {
            return false;
        }
        if (status1 == Transaction.Status.COMMITTING) {
            return status2 == Transaction.Status.COMPLETE;
        }
        if (status1 == Transaction.Status.ABORTING) {
            return status2 == Transaction.Status.COMPLETE;
        }
        if (status1 == Transaction.Status.RUNNING) {
            return status2 == Transaction.Status.COMMITTING || status2 == Transaction.Status.ABORTING || status2 == Transaction.Status.COMPLETE;
        }

        return false;
    }
}
