package vmsim.core;

import java.util.concurrent.locks.ReentrantLock;

public class PTE {

    public enum PageType { ANONYMOUS, FILE }
    public final PageType type;  // Determines pageType
    public final String filename; // Used when pageType is File
    public final int fileOffset; // Used when pageType is File

    public final int vaddr; // Virtual address of the Page table entry
    public final String processID; // Owning processID (Used in output)
    public final ReentrantLock lock; // Lock to sync access to PTE fields between Eviction

    public final boolean fundamentalWritable; // Read only field to determine if PTE represents writable data
    public boolean writable; // Current write state
    public boolean isCopyOnWrite = false; // Is it a copy on write

    public boolean inFrame; // A PTE is either inFrame , onSwap or on DISK
    public boolean onSwap; // Only in swap if anonymous page
    public boolean dirty; // Used by eviction policies
    public boolean accessed; // Used by eviction policies
    public Frame frame; // Pointer to frame if inFrame

    /**
     * Constructor for ANONYMOUS pages (e.g., stack/heap).
     */
    public PTE(int vaddr, boolean writable, String processID) {
        this.vaddr = vaddr;
        this.processID = processID;
        this.writable = writable;
        this.fundamentalWritable = writable;
        this.lock = new ReentrantLock();
        this.frame = null;

        this.type = PageType.ANONYMOUS;
        this.filename = null;
        this.fileOffset = -1; // Not applicable
        this.inFrame = false;
        this.onSwap = false;
        this.dirty = false;
        this.accessed = false;
    }

    /**
     * Constructor for FILE-BACKED pages.
     */
    public PTE(int vaddr, boolean writable, String processID, String filename, int fileOffset) {
        this.vaddr = vaddr;
        this.processID = processID;
        this.writable = writable;
        this.fundamentalWritable = writable;
        this.lock = new ReentrantLock();
        this.frame = null;

        this.type = PageType.FILE;
        this.filename = filename;
        this.fileOffset = fileOffset;
        this.inFrame = false;
        this.onSwap = false; // File-backed pages don't use swap
        this.dirty = false;
        this.accessed = false;
    }
}