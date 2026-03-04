package com.pooli.common.util;

public class MaskingUtils {
	
	public static String maskingPhoneNumber(String rawPhone) {
	      if (rawPhone == null || rawPhone.isBlank()) {
	          return rawPhone;
	      }

	      String digitsOnly = rawPhone.replaceAll("\\D", "");
	      if (digitsOnly.length() < 8) {
	          return rawPhone;
	      }

	      String prefix = digitsOnly.substring(0, digitsOnly.length() - 8);
	      String suffix = digitsOnly.substring(digitsOnly.length() - 4);

	      if (rawPhone.contains("-")) {
	          return prefix + "-****-" + suffix;
	      }
	      return prefix + "****" + suffix;
	  }
}
