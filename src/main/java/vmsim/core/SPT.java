package vmsim.core;

import vmsim.Simulator;
import vmsim.UnauthorizedAccessException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SPT {

    private final Map<Integer, PTE> entries;
    private final String processID;
    private final FrameTable frameTable;
    private final Simulator.Statistics stats;

    // 4KB pages (4096 bytes). 2^12.
    private static final int PAGE_SHIFT = 12;

    public SPT(String processID, FrameTable frameTable, Simulator.Statistics stats) {
        this.entries = new HashMap<>();
        this.processID = processID;
        this.frameTable = frameTable;
        this.stats = stats;
    }

    /**
     * Calculates the page key from a full virtual address.
     */
    private int getPageKey(int address) {
        return address >> PAGE_SHIFT;
    }

    /**
     * Returns PTE entry in the SPT on success and NULL on failure
     */
    public PTE lookup(int vaddr) {
        return entries.get(vaddr);
    }

    /**
     * Allocates a new ANONYMOUS page in the SPT.
     */
    public boolean allocateAnonymousPage(int vpn, boolean writable) {
        if (lookup(vpn) != null) {
            return false;
        }
        PTE pte = new PTE(vpn, writable, this.processID);
        entries.put(vpn, pte);
        return true;
    }

    /**
     * Allocates a new FILE-BACKED page in the SPT.
     */
    public boolean allocateFilePage(int vpn, String filename, int fileOffset, boolean writable) {
        if (lookup(vpn) != null) {
            return false;
        }
        PTE pte = new PTE(vpn, writable, this.processID, filename, fileOffset);
        entries.put(vpn, pte);
        return true;
    }


    /**
     * The main MMU logic.
     * 'Seg faults' on access to unallocated memory
     */
    public void accessMemory(int address, boolean isWrite) throws UnauthorizedAccessException {
        List<String> opLog = new ArrayList<>();
        int pageKey = getPageKey(address);
        String op = isWrite ? "WRITE" : "READ";
        int accessNum = stats.atomicallyGetNextAccessNum();

        opLog.add(String.format("--- [PID: %s] Access %d: %s at 0x%04X (Page Key %d) ---",
                this.processID, accessNum, op, address, pageKey));

        // Look up the PTE. If it's not here, the process never
        // allocated it. This is a Segmentation Fault.
        PTE pte = this.lookup(pageKey);
        if (pte == null) {
            opLog.add("-> SEGMENTATION FAULT: No page allocated at this address.");
            logOperation(opLog);
            throw new UnauthorizedAccessException("Segmentation Fault at 0x" + Integer.toHexString(address));
        }

        // The page *exists*, but the process is trying to WRITE
        // to a READ-ONLY page. This is a Protection Fault.
        if (isWrite && !pte.writable) {
            opLog.add("-> PROTECTION FAULT: Tried to write to read-only page.");
            logOperation(opLog);
            throw new UnauthorizedAccessException("Protection Fault at 0x" + Integer.toHexString(address));
        }

        pte.lock.lock();
        try {
            if (!pte.inFrame) {
                // --- PAGE FAULT ---
                opLog.add("-> PAGE FAULT for Page Key " + pageKey);

                if (this.frameTable.frame_get_page(pte, opLog, isWrite)) {
                    opLog.add("-> Page loaded.");
                    this.stats.recordFault();
                } else {
                    opLog.add("-> !! Page load FAILED !!");
                }

            } else {
                // --- PAGE HIT ---
                opLog.add("-> PAGE HIT for Page Key " + pageKey + " in Frame " + pte.frame.kpage);
                this.stats.recordHit();

                this.frameTable.onAccess(pte);

                if (isWrite) {
                    pte.dirty = true;
                }
            }
        } finally {
            pte.lock.unlock();
        }

        opLog.add(this.frameTable.toString());
        logOperation(opLog);
    }

    /**
     * A synchronized logger for atomic log blocks.
     */
    private void logOperation(List<String> opLog) {
        synchronized (System.out) {
            for (String line : opLog) {
                System.out.println(line);
            }
            System.out.println();
        }
    }


    /**
     * Destroys the SPT, freeing all associated resources.
     */
    public void destroy() {
        for (PTE pte : entries.values()) {
            Frame frameToFree = null;


            // Pin the page while holding the lock to stop eviction
            // If we hold the pte lock we are guaranteed to not be pinned
            // as eviction needs to hold the pte lock to pin
            pte.lock.lock();
            try {
                if (pte.inFrame) {
                    frameToFree = pte.frame;
                    frameToFree.pin();
                }
            } finally {
                pte.lock.unlock();
            }

            if (frameToFree != null) {
                // Handle file writeback on process exit
                if (pte.type == PTE.PageType.FILE && pte.writable && pte.dirty) {
                    // This log is not part of an op, so it's unsynchronized,
                    // but it only happens at the end of a process.
                    System.out.println("SPT(Destroy): Writing back FILE page " + pte.vaddr + " (offset " + pte.fileOffset + ") to " + pte.filename);
                }
                this.frameTable.freeFrame(frameToFree, pte);
            }
        }
        entries.clear();
    }
}