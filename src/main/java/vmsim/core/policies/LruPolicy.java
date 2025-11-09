package vmsim.core.policies;

import vmsim.core.Frame;
import vmsim.core.PTE;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LruPolicy implements EvictionPolicy {

    private ArrayList<Frame> frameList;
    private int userPoolSize;
    private final AtomicLong accessTime = new AtomicLong(0);

    @Override
    public void init(ArrayList<Frame> frameList, int userPoolSize) {
        this.frameList = frameList;
        this.userPoolSize = userPoolSize;
    }

    @Override
    public int findVictim(List<String> opLog) {
        while (true) {
            Frame victimCandidate = null;
            long minTime = Long.MAX_VALUE;

            for (Frame frame : frameList) {
                if (!frame.pinned && !frame.isFree()) {
                    if (frame.policyTimestamp < minTime) {
                        minTime = frame.policyTimestamp;
                        victimCandidate = frame;
                    }
                }
            }

            if (victimCandidate == null) {
                continue;
            }

            Frame victimFrame = victimCandidate;
            victimFrame.lock.lock();

            try {
                if (victimFrame.pinned || victimFrame.isFree() || victimFrame.policyTimestamp > minTime) {
                    continue;
                }

                PTE victimPTE = victimFrame.pte;
                if (victimPTE == null) {
                    continue;
                }

                if (!victimPTE.lock.tryLock()) {
                    continue;
                }

                try {
                    if (victimFrame.pinned || victimFrame.pte != victimPTE || !victimPTE.inFrame) {
                        continue;
                    }

                    // --- VICTIM FOUND ---
                    log("Evicting VPN " + victimPTE.vaddr + " (LRU time=" + victimFrame.policyTimestamp + ") from Frame " + victimFrame.kpage, opLog);

                    if (victimPTE.dirty) {
                        if (victimPTE.type == PTE.PageType.FILE && victimPTE.writable) {
                            log("WRITEBACK: FILE Page " + victimPTE.vaddr + " (offset " + victimPTE.fileOffset + ") to " + victimPTE.filename, opLog);
                        } else if (victimPTE.type == PTE.PageType.ANONYMOUS) {
                            log("WRITEBACK: ANONYMOUS Page " + victimPTE.vaddr + " is dirty, saving to swap.", opLog);
                            victimPTE.onSwap = true;
                        }
                    }

                    victimPTE.inFrame = false;
                    victimPTE.frame = null;
                    victimFrame.pte = null;

                    victimFrame.pinned = true;

                    return victimFrame.kpage;

                } finally {
                    victimPTE.lock.unlock();
                }
            } finally {
                victimFrame.lock.unlock();
            }
        }
    }

    @Override
    public void onAccess(PTE pte) {
        if (pte.frame != null) {
            pte.frame.policyTimestamp = accessTime.getAndIncrement();
        }
    }

    @Override
    public void onLoad(Frame frame) {
        frame.policyTimestamp = accessTime.getAndIncrement();
    }

    private void log(String message, List<String> opLog) {
        opLog.add("FrameTable(LRU): " + message);
    }
}