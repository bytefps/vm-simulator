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
     * Resolves a Copy-on-Write fault.
     * MUST be called while holding the lock for the faulting PTE.
     * Detaches the PTE from its shared frame and gives it a new
     * private, writable frame with the same data.
     *
     * This is safe because eviction uses tryLock() and will not block.
     */
    private void handleCoWFault(PTE pte, List<String> opLog) {
        // We are holding pte.lock

        // old frame CANNOT be evicted as we hold the LOCK
        Frame oldFrame = pte.frame;

        if (oldFrame == null) {
            // Page was CoW'd, then evicted before a write.
            // This is just a normal fault now.
            opLog.add("-> CoW: Page was not in frame. Converting to normal page fault.");
            pte.isCopyOnWrite = false;
            pte.writable = pte.fundamentalWritable;
            return;
        }

        //  Detach this PTE from the shared frame's list
        int oldFrameKpage = oldFrame.kpage; // Store this for copy
        oldFrame.lock.lock(); // Lock frame (PTE -> Frame order, safe )
        try {
            oldFrame.pin();
            oldFrame.ptes.remove(pte);
        } finally {
            oldFrame.lock.unlock();
        }

        // Old frame could have been evicted if we did not pin

        // Trick the frame table into loading data into a new frame
        pte.inFrame = false;
        pte.frame = null;

        //  Load the page into a new private frame.
        // We are still holding the PTE lock
        opLog.add("-> CoW: Copying page " + pte.vaddr + " to new private frame.");

        // This is the call that can evict. It's safe due to tryLock().
        if (!this.frameTable.frame_get_cow_page(pte, oldFrameKpage, opLog, true)) {
            // This really shouldn't fail unless getFrame fails
            // KERNEL PANIC
            throw new RuntimeException("CoW: frame_get_page failed to load private frame");
        }

        // Copy is done we can now unpin it safely, get evicted, I DONT CARE
        oldFrame.lock.lock();
        try {
            oldFrame.unpin();
        } finally {
            oldFrame.lock.unlock();
        }

        // Update PTE state
        // pte.inFrame and pte.frame were set by frame_get_page.
        pte.isCopyOnWrite = false;
        pte.writable = pte.fundamentalWritable;
        // pte.dirty will be set by the caller (accessMemory)
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
        // to a READ-ONLY page. This is a Protection Fault. --new-- or COW fault
        pte.lock.lock();
        try {
            boolean isCoWFault = false;

            if (isWrite && !pte.writable) {
                // Is it a COW Fault or an actual protection fault?
                if (pte.isCopyOnWrite) {
                    opLog.add("-> COPY-ON-WRITE FAULT for Page Key " + pageKey);
                    // This will give pte a new private frame.
                    handleCoWFault(pte, opLog);
                    this.stats.recordCoWFault();
                    isCoWFault = true;
                    // The fault is now resolved. pte.writable is true.
                    // We can now continue to the hit/fault logic below.
                } else {
                    // --- No, it's a REAL Protection Fault. ---
                    opLog.add("-> PROTECTION FAULT: Tried to write to read-only page.");
                    logOperation(opLog);
                    throw new UnauthorizedAccessException("Protection Fault at 0x" + Integer.toHexString(address));
                }
            }

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
                    if (!isCoWFault) {
                        opLog.add("-> PAGE HIT for Page Key " + pageKey + " in Frame " + pte.frame.kpage);
                        this.stats.recordHit();
                        this.frameTable.onAccess(pte);
                    }
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

    public boolean setupCopyOnWrite(int newVpn, int sourceVpn) {
        PTE sourcePTE = lookup(sourceVpn);
        if (sourcePTE == null) {
            return false; // Source doesn't exist
        }
        if (lookup(newVpn) != null) {
            return false; // Destination already allocated
        }

        // We must lock the source PTE to safely copy its state
        sourcePTE.lock.lock();
        try {
            // Create the new PTE. It must match the source's type.
            PTE newPTE;
            if (sourcePTE.type == PTE.PageType.ANONYMOUS) {
                newPTE = new PTE(newVpn, sourcePTE.fundamentalWritable, this.processID);
            } else {
                newPTE = new PTE(newVpn, sourcePTE.fundamentalWritable, this.processID, sourcePTE.filename, sourcePTE.fileOffset);
            }

            // Mark both as CoW pages - Lie about there being 'two' copies
            sourcePTE.isCopyOnWrite = true;
            newPTE.isCopyOnWrite = true;

            // Set them as read only so they fault when written to
            sourcePTE.writable = false;
            newPTE.writable = false;

            // Make newPTE share the same data source (frame or swap)
            newPTE.inFrame = sourcePTE.inFrame;
            newPTE.frame = sourcePTE.frame;
            newPTE.onSwap = sourcePTE.onSwap;

            // If it's in a frame, add newPTE as a sharer
            if (newPTE.inFrame) {
                Frame sharedFrame = newPTE.frame;
                sharedFrame.lock.lock();
                try {
                    sharedFrame.ptes.add(newPTE);
                } finally {
                    sharedFrame.lock.unlock();
                }
            }

            entries.put(newVpn, newPTE);
            return true;

        } finally {
            sourcePTE.lock.unlock();
        }
    }

    public SPT fork() {
        // Create the new SPT for the child
        SPT childSPT = new SPT(
                this.processID + "-child",
                this.frameTable,
                this.stats
        );

        //  Iterate over every *parent* PTE and create a
        //    shared CoW mapping in the child.
        for (PTE parentPTE : this.entries.values()) {
            parentPTE.lock.lock();
            try {
                //  Create a matching PTE for the child
                PTE childPTE;
                if (parentPTE.type == PTE.PageType.ANONYMOUS) {
                    childPTE = new PTE(parentPTE.vaddr, parentPTE.fundamentalWritable, childSPT.processID);
                } else {
                    childPTE = new PTE(parentPTE.vaddr, parentPTE.fundamentalWritable, childSPT.processID, parentPTE.filename, parentPTE.fileOffset);
                }

                // Set up the CoW
                parentPTE.isCopyOnWrite = true;
                childPTE.isCopyOnWrite = true;

                parentPTE.writable = false;
                childPTE.writable = false;

                // Make the child PTE share the parent's data
                childPTE.inFrame = parentPTE.inFrame;
                childPTE.frame = parentPTE.frame;
                childPTE.onSwap = parentPTE.onSwap;

                //  If it's in a frame, add the child as a sharer
                if (childPTE.inFrame) {
                    Frame sharedFrame = childPTE.frame;
                    sharedFrame.lock.lock(); // pte.lock -> frame.lock
                    try {
                        sharedFrame.ptes.add(childPTE);
                    } finally {
                        sharedFrame.lock.unlock();
                    }
                }

                // Add the new PTE to the child's table
                childSPT.entries.put(childPTE.vaddr, childPTE);

            } finally {
                parentPTE.lock.unlock();
            }
        }
        return childSPT;
    }
}