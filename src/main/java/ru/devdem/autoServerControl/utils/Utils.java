package ru.devdem.autoServerControl.utils;

import java.util.Set;

public class Utils {
    public static String getAliasesFromSet(Set<String> aliases) {
        String ans = "";
        for (String s : aliases) {
            ans=ans.concat(", " + s);
        }
        if (ans.startsWith(", ")) {
            ans = ans.substring(2);
        }
        return ans;
    }
}
