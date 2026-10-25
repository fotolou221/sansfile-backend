package com.sansfile.app.service.custom.otp;

import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Numéros de téléphone des comptes SansFile, au format international E.164 (ex. +221771234567).
 * Le Sénégal reste la norme ; une liste fermée de pays est ouverte à la connexion par SMS.
 */
public final class PhoneNumbers {

    public static final String SENEGAL_CODE = "221";
    private static final Pattern SENEGAL_MOBILE = Pattern.compile("^\\+2217[05678]\\d{7}$");
    private static final Pattern E164 = Pattern.compile("^\\+[1-9]\\d{7,14}$");

    /**
     * Indicatifs ouverts à la connexion par SMS : pays voisins et diaspora. Liste fermée exprès : accepter
     * n'importe quel pays permettrait de vider le crédit SMS avec des numéros surtaxés (fraude aux SMS).
     * Doit rester identique à la liste de l'écran de connexion (sansfile-client, shared/phone/countries.ts).
     */
    public static final List<String> ALLOWED_COUNTRY_CODES = List.of(
        // Afrique de l'Ouest et centrale
        "221",
        "220",
        "222",
        "223",
        "224",
        "225",
        "226",
        "227",
        "228",
        "229",
        "238",
        "245",
        "237",
        "241",
        "242",
        "243",
        // Maghreb
        "212",
        "213",
        "216",
        // Europe
        "33",
        "32",
        "41",
        "352",
        "34",
        "39",
        "49",
        "44",
        "351",
        // Amérique du Nord (États-Unis, Canada)
        "1"
    );

    private static final List<String> CODES_LONGEST_FIRST = ALLOWED_COUNTRY_CODES.stream()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .toList();

    private PhoneNumbers() {}

    /**
     * Met un numéro au format +XXXXXXXX : « 00 » devient « + », et un numéro sénégalais saisi sans indicatif
     * (9 chiffres, ou commençant par 221) reçoit +221.
     */
    public static String normalize(String rawPhone) {
        if (rawPhone == null) {
            return "";
        }
        String digits = rawPhone.replaceAll("[^0-9+]", "").trim();
        if (digits.startsWith("00")) {
            digits = "+" + digits.substring(2);
        } else if (!digits.startsWith("+")) {
            if (digits.startsWith(SENEGAL_CODE)) {
                digits = "+" + digits;
            } else if (digits.length() == 9) {
                digits = "+" + SENEGAL_CODE + digits;
            }
        }
        return digits;
    }

    public static boolean isSenegalese(String phone) {
        return phone != null && phone.startsWith("+" + SENEGAL_CODE);
    }

    /** Indicatif (sans « + ») d'un numéro E.164 s'il fait partie des pays ouverts, sinon {@code null}. */
    public static String allowedCountryCodeOf(String phone) {
        if (phone == null || !phone.startsWith("+")) {
            return null;
        }
        return CODES_LONGEST_FIRST.stream()
            .filter(code -> phone.startsWith("+" + code))
            .findFirst()
            .orElse(null);
    }

    /** Numéro autorisé pour la connexion par SMS : mobile sénégalais, ou numéro d'un pays ouvert. */
    public static boolean isAllowedForSms(String phone) {
        if (phone == null) {
            return false;
        }
        if (isSenegalese(phone)) {
            return SENEGAL_MOBILE.matcher(phone).matches();
        }
        return E164.matcher(phone).matches() && allowedCountryCodeOf(phone) != null;
    }
}
