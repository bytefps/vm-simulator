package vmsim.core.policies;

import vmsim.core.Frame;
import vmsim.core.PTE;

import java.util.ArrayList;
import java.util.List;

public class ClockPolicy implements EvictionPolicy {

    private ArrayList<Frame> frameList;
    private int userPoolSize;
    private int clockHand = 0;

    @Override
    public void init(ArrayList<Frame> frameList, int userPoolSize) {
        this.frameList = frameList;
        this.userPoolSize = userPoolSize;
    }

    @Override
    public int findVictim(List<String> opLog) {
        while (true) {
            Frame victimFrame = frameList.get(clockHand);
            advanceClockHand();

            victimFrame.lock.lock();
            try {
                if (victimFrame.pinned || victimFrame.isFree()) {
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

                    if (victimPTE.accessed) {
                        victimPTE.accessed = false;
                        continue;
                    }

                    // --- VICTIM FOUND ---
                    log("Evicting VPN " + victimPTE.vaddr + " from Frame " + victimFrame.kpage, opLog);

                    if (victimPTE.dirty) {
                        if (victimPTE.type == PTE.PageType.FILE && victimPTE.writable) {
                            log("WRITEBACK: FILE Page " + victimPTE.vaddr + " (offset " + victimPTE.fileOffset + ") to " + victimPTE.filename, opLog);
                            // In a real OS, we'd write to the file.
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

    private void advanceClockHand() {
        clockHand = (clockHand + 1) % userPoolSize;
    }

    @Override
    public void onAccess(PTE pte) {
        pte.accessed = true;
    }

    @Override
    public void onLoad(Frame frame) {
        // Do nothing
    }

    private void log(String message, List<String> opLog) {
        opLog.add("FrameTable(Clock): " + message);
    }
}