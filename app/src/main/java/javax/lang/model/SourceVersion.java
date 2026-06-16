package javax.lang.model;

// Stub for Android: javax.lang.model is part of the Java compiler API, unavailable on Android.
// GraphHopper uses SourceVersion.isIdentifier() only for name validation in IntEncodedValueImpl.
public enum SourceVersion {
    RELEASE_0;

    public static boolean isIdentifier(CharSequence name) {
        String id = name.toString();
        if (id.isEmpty()) return false;
        int cp = id.codePointAt(0);
        if (!Character.isJavaIdentifierStart(cp)) return false;
        for (int i = Character.charCount(cp); i < id.length(); i += Character.charCount(cp)) {
            cp = id.codePointAt(i);
            if (!Character.isJavaIdentifierPart(cp)) return false;
        }
        return true;
    }

    public static boolean isKeyword(CharSequence s) { return false; }
    public static boolean isName(CharSequence name) { return isIdentifier(name); }
    public static SourceVersion latest() { return RELEASE_0; }
    public static SourceVersion latestSupported() { return RELEASE_0; }
}
