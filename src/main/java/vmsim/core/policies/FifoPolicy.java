package vmsim.core.policies;

import vmsim.core.Frame;
import vmsim.core.PTE;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FifoPolicy implements EvictionPolicy {

    private ArrayList<Frame> frameList;
    private int userPoolSize;
    private final ConcurrentLinkedQueue<Frame> fifoQueue = new ConcurrentLinkedQueue<>();

    @Override
    public void init(ArrayList<Frame> frameList, int userPoolSize) {
        this.frameList = frameList;
        this.userPoolSize = userPoolSize;
    }

    @Override
    public int findVictim(List<String> opLog) {
        while (true) {
            Frame victimFrame = fifoQueue.poll();
            if (victimFrame == null) {
                victimFrame = frameList.get(0);
            }

            victimFrame.lock.lock();
            try {
                if (victimFrame.pinned || victimFrame.isFree()) {
                    if (!victimFrame.isFree()) {
                        fifoQueue.add(victimFrame);
                    }
                    continue;
                }

                PTE victimPTE = victimFrame.pte;
                if (victimPTE == null) {
                    continue;
                }

                if (!victimPTE.lock.tryLock()) {
                    fifoQueue.add(victimFrame);
                    continue;
                }

                try {
                    if (victimFrame.pinned || victimFrame.pte != victimPTE || !victimPTE.inFrame) {
                        fifoQueue.add(victimFrame);
                        continue;
                    }

                    // --- VICTIM FOUND ---
                    log("Evicting VPN " + victimPTE.vaddr + " from Frame " + victimFrame.kpage, opLog);

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
        // Do nothing
    }

    @Override
    public void onLoad(Frame frame) {
        fifoQueue.add(frame);
    }

    private void log(String message, List<String> opLog) {
        opLog.add("FrameTable(FIFO): " + message);
    }
}