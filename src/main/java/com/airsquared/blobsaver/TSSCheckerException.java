package com.airsquared.blobsaver;

import javafx.scene.control.TextField;

/**
 * RuntimeException for all tsschecker related errors.
 */
public abstract class TSSCheckerException extends RuntimeException {

    private final static String ASK_TO_REPORT_BUG = "\n\nPlease create a new issue on Github or PM me on Reddit. " +
            "The log has been copied to your clipboard.";

    /**
     * Constructs a new tsschecker exception with the specified detail message.
     * The cause is not initialized, and may subsequently be initialized by a
     * call to {@link #initCause}. TODO: update this javadoc
     *
     * @param message the detail message. The detail message is saved for
     *                later retrieval by the {@link #getMessage()} method.
     */
    public TSSCheckerException(String message, boolean appendMessage) {
        super(appendMessage ? message + ASK_TO_REPORT_BUG : message);
    }

    public static class Reportable extends TSSCheckerException {

        private TextField invalidElement;

        private Exception originalException;
        
        public Reportable(String message, boolean appendMessage) {
            super(message, appendMessage);
        }

        public Reportable(String message, Exception originalException, boolean appendMessage) {
            this(message, appendMessage);
            this.originalException = originalException;
        }

        public Reportable(String message, String invalidElement, boolean appendMessage) {
            this(message, appendMessage);
            this.invalidElement = (TextField) Main.primaryStage.getScene().lookup(invalidElement);
        }

        public Exception getOriginalException() {
            return originalException;
        }

        public TextField getInvalidElement() {
            return invalidElement;
        }
    }

    public static class Unreportable extends TSSCheckerException {

        private TextField invalidElement;

        public Unreportable(String message, boolean appendMessage) {
            super(message, appendMessage);
        }

        public Unreportable(String message, String invalidElement, boolean appendMessage) {
            this(message, appendMessage);
            this.invalidElement = (TextField) Main.primaryStage.getScene().lookup(invalidElement);
        }

        public TextField getInvalidElement() {
            return invalidElement;
        }

    }

//    /**
//     * Constructs a new tsschecker exception with the specified detail message and
//     * cause.  <p>Note that the detail message associated with
//     * {@code cause} is <i>not</i> automatically incorporated in
//     * this runtime exception's detail message.
//     *
//     * @param message the detail message (which is saved for later retrieval
//     *                by the {@link #getMessage()} method).
//     * @param cause   the cause (which is saved for later retrieval by the
//     *                {@link #getCause()} method).  (A <tt>null</tt> value is
//     *                permitted, and indicates that the cause is nonexistent or
//     *                unknown.)
//     */
//    public TSSCheckerException(String message, Throwable cause) {
//        super(message, cause);
//    }
//
//    /**
//     * Constructs a new tsschecker exception with the specified cause and a
//     * detail message of <tt>(cause==null ? null : cause.toString())</tt>
//     * (which typically contains the class and detail message of
//     * <tt>cause</tt>).  This constructor is useful for runtime exceptions
//     * that are little more than wrappers for other throwables.
//     *
//     * @param cause the cause (which is saved for later retrieval by the
//     *              {@link #getCause()} method).  (A <tt>null</tt> value is
//     *              permitted, and indicates that the cause is nonexistent or
//     *              unknown.)
//     */
//    public TSSCheckerException(Throwable cause) {
//        super(cause);
//    }
//
//    /**
//     * Constructs a new tsschecker exception with the specified detail
//     * message, cause, suppression enabled or disabled, and writable
//     * stack trace enabled or disabled.
//     *
//     * @param message            the detail message.
//     * @param cause              the cause.  (A {@code null} value is permitted,
//     *                           and indicates that the cause is nonexistent or unknown.)
//     * @param enableSuppression  whether or not suppression is enabled
//     *                           or disabled
//     * @param writableStackTrace whether or not the stack trace should
//     *                           be writable
//     */
//    protected TSSCheckerException(String message, Throwable cause,
//                                  boolean enableSuppression,
//                                  boolean writableStackTrace) {
//        super(message, cause, enableSuppression, writableStackTrace);
//    }

    public static TSSCheckerException invalidURLException(String URL) {
        return new TSSCheckerException.Unreportable("\"" + URL + "\" is not a valid URL.\n\n" +
                "Make sure it starts with \"http://\" or \"https://\", has \"apple\" in it, " +
                "and ends with \".ipsw\"",
                true);
    }

    public boolean isReportable() {
        return this instanceof Reportable;
    }
}