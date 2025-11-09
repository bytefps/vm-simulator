package vmsim;

import vmsim.core.SPT;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Scanner;

/**
 * Represents a single "process" that runs on a thread.
 * Each process has its own trace file and its own SPT
 */
public class SimulatedProcess implements Runnable {

    // Global "kernel" services
    private final Simulator simulator;

    // Per-process state
    private final String traceFile;
    private final SPT spt;
    private final String processID;

    // 4KB pages (4096 bytes). 2^12.
    private static final int PAGE_SHIFT = 12;

    public SimulatedProcess(Simulator simulator, String traceFile) {
        this.simulator = simulator;
        this.traceFile = traceFile;
        this.processID = new File(traceFile).getName(); // Use file name as PID

        // Create this process's SPT and give it access to the
        // kernel's shared resources (FrameTable and Stats)
        this.spt = new SPT(this.processID, simulator.getFrameTable(), simulator.getStats());
    }

    @Override
    public void run() {
        try {
            simulator.log("--- Process " + processID + " starting. ---");

            try (Scanner scanner = new Scanner(new File(traceFile))) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine().trim();
                    if (line.isEmpty() || line.startsWith("#")) {
                        continue;
                    }

                    String[] parts = line.split("\\s+");
                    if (parts.length < 2) {
                        System.err.println("FATAL: [" + processID + "] Invalid trace line: " + line);
                        return;
                    }

                    String command = parts[0].toUpperCase();
                    int address = -1;
                    int pageKey = -1;
                    boolean isWritable = false;
                    String filename = null;
                    int fileOffset = -1;

                    // Parse address and page key (needed by all commands)
                    try {
                        // All commands must have a valid address
                        address = Integer.parseInt(parts[1].substring(0), 16);
                        pageKey = address >> PAGE_SHIFT;
                    } catch (NumberFormatException e) {
                        System.err.println("FATAL: [" + processID + "] Invalid address: " + parts[1]);
                        return;
                    } catch (Exception e) {
                        System.err.println("FATAL: [" + processID + "] Error parsing line: " + line);
                        return;
                    }


                    // R and W commands are wrapped in a try/catch.
                    try {
                        switch (command) {
                            case "A": // Allocate Anonymous
                                if (parts.length < 3) {
                                    System.err.println("FATAL: [" + processID + "] Invalid 'A' command: " + line);
                                    return;
                                }
                                isWritable = parts[2].equalsIgnoreCase("W");
                                if (!spt.allocateAnonymousPage(pageKey, isWritable)) {
                                    simulator.log("FATAL [PID: " + processID + "]: Page " + pageKey + " is already allocated.");
                                    return; // Kill this thread
                                }
                                break;

                            case "F": // Allocate File-backed
                                if (parts.length < 5) {
                                    System.err.println("FATAL: [" + processID + "] Invalid 'F' command: " + line);
                                    return;
                                }
                                filename = parts[2];
                                fileOffset = Integer.parseInt(parts[3]); // Parse the offset
                                isWritable = parts[4].equalsIgnoreCase("W");
                                if (!spt.allocateFilePage(pageKey, filename, fileOffset, isWritable)) {
                                    simulator.log("FATAL [PID: " + processID + "]: Page " + pageKey + " is already allocated.");
                                    return; // Kill this thread
                                }
                                break;

                            case "R": // Read
                                this.spt.accessMemory(address, false);
                                break;

                            case "W": // Write
                                this.spt.accessMemory(address, true);
                                break;

                            default:
                                System.err.println("FATAL: [" + processID + "] Invalid command: " + command);
                                return;
                        }
                    } catch (UnauthorizedAccessException e) {
                        // The kernel kills used
                        // This is a "segfault" or "protection fault".
                        simulator.log("FATAL [PID: " + processID + "]: " + e.getMessage());
                        return; // Kill this thread
                    } catch (Exception e) {
                        // Catch other parsing errors (e.g., bad fileOffset)
                        System.err.println("FATAL: [" + processID + "] Error processing line: " + line);
                        e.printStackTrace();
                        return;
                    }
                    // ---------------------------------

                }
            } catch (FileNotFoundException e) {
                System.err.println("FATAL: [" + processID + "] Trace file not found: " + traceFile);
            } catch (Exception e) {
                System.err.println("FATAL: [" + processID + "] Runtime error: " + e);
                e.printStackTrace();
            }
        } finally {
            simulator.log("--- Process " + processID + " finished. Cleaning up resources... ---");
            if (spt != null) {
                spt.destroy();
            }
        }
    }
}