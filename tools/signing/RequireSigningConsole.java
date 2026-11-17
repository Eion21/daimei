// Public preflight only. Never request, accept, or store a password.
final class RequireSigningConsole {
    public static void main(String[] args) {
        if (System.console() == null) {
            System.err.println("STOP: no private Java console; do not invoke apksigner or enter a password.");
            System.exit(2);
        }
    }
}
