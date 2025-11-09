package vmsim;

/**
 * A custom exception to represent a "segfault" or "protection fault".
 * This is thrown by the SPT when a process attempts an illegal access.
 */
public class UnauthorizedAccessException extends Exception {
  public UnauthorizedAccessException(String message) {
    super(message);
  }
}