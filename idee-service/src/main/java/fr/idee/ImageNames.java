package fr.idee;

import java.text.Normalizer;
import java.util.Locale;
import java.util.regex.Pattern;

final class ImageNames {
    private ImageNames() { }
    private static final Pattern FILE=Pattern.compile("(?:[a-f0-9]{64}|[a-z0-9]+(?:-[a-z0-9]+)*-(?:[a-f0-9]{16}|[a-f0-9]{64}))\\.(?:jpg|png|gif|webp|avif)");
    static boolean valid(String filename) { return filename!=null && filename.length()<=160 && FILE.matcher(filename).matches(); }
    static String hash(String filename) {
        if (!filename.matches("[a-f0-9]{64}\\.(jpg|png|gif|webp|avif)")) throw new IllegalArgumentException("Invalid historic image filename");
        return filename.substring(0,64);
    }
    static String descriptive(String title,String city,String hash,String extension) {
        String name=(title==null ? "" : title)+" "+(city==null ? "" : city);
        name=Normalizer.normalize(name.replace("œ","oe").replace("Œ","Oe").replace("æ","ae").replace("Æ","Ae").replace("ß","ss"),Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+","").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+","-").replaceAll("^-|-$","");
        if (name.length()>80) name=name.substring(0,80).replaceAll("-+$","");
        if (name.isBlank()) name="sortie";
        return name+"-"+hash+extension;
    }
}
