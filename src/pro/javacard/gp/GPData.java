package pro.javacard.gp;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import javax.smartcardio.CardException;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;

import pro.javacard.gp.GPKeySet.Diversification;
import pro.javacard.gp.GPKeySet.GPKey;
import apdu4j.HexUtils;

public class GPData {

	public enum KeyType {
		// ID is as used in diversification/derivation
		// That is - one based.
		ENC(1), MAC(2), KEK(3), RMAC(4);

		private final int value;

		private KeyType(int value) {
			this.value = value;
		}

		public byte getValue() {
			return (byte) (value & 0xFF);
		}
	};

	// GP 2.1.1 9.1.6
	// GP 2.2.1 11.1.8
	public static String get_key_type_coding_string(int type) {
		if ((0x00 <= type) && (type <= 0x7f))
			return "Reserved for private use";
		// symmetric
		if (0x80 == type)
			return "DES - mode (ECB/CBC) implicitly known";
		if (0x81 == type)
			return "Reserved (Triple DES)";
		if (0x82 == type)
			return "￼Triple DES in CBC mode";
		if (0x83 == type)
			return "￼DES in ECB mode";
		if (0x84 == type)
			return "￼DES in CBC mode";
		if (0x85 == type)
			return "￼Pre-Shared Key for Transport Layer Security";
		if (0x88 == type)
			return "￼AES (16, 24, or 32 long keys)";
		if (0x90 == type)
			return "￼HMAC-SHA1 – length of HMAC is implicitly known";
		if (0x91 == type)
			return "MAC-SHA1-160 – length of HMAC is 160 bits";
		if (type == 0x86 || type == 0x87 || ((0x89 <= type) && (type <= 0x8F)) || ((0x92 <= type) && (type <= 0x9F)))
			return "RFU (asymmetric algorithms)";
		// asymmetric
		if (0xA0 == type)
			return "RSA Public Key - public exponent e component (clear text)";
		if (0xA1 == type)
			return "RSA Public Key - modulus N component (clear text)";
		if (0xA2 == type)
			return "RSA Private Key - modulus N component";
		if (0xA3 == type)
			return "RSA Private Key - private exponent d component";
		if (0xA4 == type)
			return "RSA Private Key - Chinese Remainder P component";
		if (0xA5 == type)
			return "RSA Private Key - Chinese Remainder Q component";
		if (0xA6 == type)
			return "RSA Private Key - Chinese Remainder PQ component";
		if (0xA7 == type)
			return "RSA Private Key - Chinese Remainder DP1 component";
		if (0xA8 == type)
			return "RSA Private Key - Chinese Remainder DQ1 component";
		if ((0xA9 <= type) && (type <= 0xFE))
			return "RFU (asymmetric algorithms)";
		if (0xFF == type)
			return "Extened Format";

		return "UNKNOWN";
	}

	// Print the key template
	public static void pretty_print_key_template(List<GPKeySet.GPKey> list, PrintStream out) {
		boolean factory_keys = false;
		out.flush();
		for (GPKey k: list) {
			out.println("VER:" + k.getVersion() + " ID:" + k.getID() + " TYPE:"+ k.getType() + " LEN:" + k.getLength());
			if (k.getVersion() == 0x00 || k.getVersion() == 0xFF)
				factory_keys = true;
		}
		if (factory_keys) {
			out.println("Key version suggests factory keys");
		}
		out.flush();
	}

	// GP 2.1.1 9.3.3.1
	public static List<GPKeySet.GPKey> get_key_template_list(byte[] data, short offset) throws GPException {

		// Return empty list if no data from card.
		// FIXME: not really a clean solution
		if (data == null)
			return new ArrayList<GPKey>();
		// Expect template 0x0E
		offset = TLVUtils.skip_tag_or_throw(data, offset, (byte) 0xe0);
		offset = TLVUtils.skipLength(data, offset);

		ArrayList<GPKeySet.GPKey> list = new ArrayList<GPKey>();
		while (offset < data.length) {
			// Objects with tag 0xC0
			offset = TLVUtils.skipTag(data, offset, (byte) 0xC0);
			int component_len = offset + TLVUtils.get_length(data, offset);
			offset = TLVUtils.skipLength(data, offset);

			int id = TLVUtils.get_byte_value(data, offset);
			offset++;
			int version = TLVUtils.get_byte_value(data, offset);
			offset++;
			while (offset < component_len) {
				// Check for extended format here.
				int type = TLVUtils.get_byte_value(data, offset);
				offset++;
				if (type == 0xFF) {
					throw new GPException("Extended format key template not yet supported!");
				}
				int length = TLVUtils.get_byte_value(data, offset);
				offset++;
				list.add(new GPKey(version, id, length, type));
				break; // FIXME:
			}
		}
		return list;
	}


	// GP 2.1.1: F.2 Table F-1
	public static void pretty_print_card_data(byte[] data, PrintStream out) {
		if (data == null) {
			out.println("NO CARD DATA");
			return;
		}
		try {
			short offset = 0;
			offset = TLVUtils.skipTagAndLength(data, offset, (byte) 0x66);
			offset = TLVUtils.skipTagAndLength(data, offset, (byte) 0x73);
			while (offset < data.length) {
				int tag = TLVUtils.getTLVTag(data, offset);
				if (tag == 0x06) {
					String oid = ASN1ObjectIdentifier.fromByteArray(TLVUtils.getTLVAsBytes(data, offset)).toString();
					if (oid.equals("1.2.840.114283.1"))
						out.println("GlobalPlatform card");
				} else if (tag == 0x60) {
					out.println("Version: " + gp_version_from_tlv(data, offset));
				} else if (tag == 0x63) {
					out.println("TAG3: " + ASN1ObjectIdentifier.fromByteArray(TLVUtils.getTLVValueAsBytes(data, offset)));
				} else if (tag == 0x64) {
					out.println("SCP version: " + gp_scp_version_from_tlv(data, offset));
				} else if (tag == 0x65) {
					out.println("TAG5: " + ASN1ObjectIdentifier.fromByteArray(TLVUtils.getTLVValueAsBytes(data, offset)));
				} else if (tag == 0x66) {
					out.println("TAG6: " + ASN1ObjectIdentifier.fromByteArray(TLVUtils.getTLVValueAsBytes(data, offset)));
				} else {
					out.println("Unknown tag: " + Integer.toHexString(tag));
				}
				offset = TLVUtils.skipAnyTag(data, offset);
			}
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	private static String gp_version_from_tlv(byte[] data, short offset) {
		try {
			String oid;
			oid = ASN1ObjectIdentifier.fromByteArray(TLVUtils.getTLVValueAsBytes(data, offset)).toString();
			if (oid.startsWith("1.2.840.114283.2")) {
				return oid.substring("1.2.840.114283.2.".length());
			} else {
				return "unknown";
			}
		} catch (IOException e) {
			return "error";
		}
	}

	private static String gp_scp_version_from_tlv(byte[] data, short offset) {
		try {
			String oid;
			oid = ASN1ObjectIdentifier.fromByteArray(TLVUtils.getTLVValueAsBytes(data, offset)).toString();
			if (oid.startsWith("1.2.840.114283.4")) {
				String[] p = oid.substring("1.2.840.114283.4.".length()).split("\\.");
				return "SCP_0" +p[0] + "_" + String.format("%02x",Integer.valueOf(p[1]));
			} else {
				return "unknown";
			}
		} catch (IOException e) {
			return "error";
		}
	}

	public static void get_global_platform_version(byte[] data) {
		short offset = 0;
		offset = TLVUtils.skipTagAndLength(data, offset, (byte) 0x66);
		offset = TLVUtils.skipTagAndLength(data, offset, (byte) 0x73);
		offset = TLVUtils.findTag(data, offset, (byte) 0x60);
	}


	public static Diversification suggestDiversification(byte[] cplc) {
		if (cplc != null) {
			// G&D
			if (cplc[7] == 0x16 && cplc[8] == 0x71)
				return Diversification.EMV;
			// TODO: Gemalto
		}
		return Diversification.NONE;
	}


	public static void pretty_print_cplc(byte [] data, PrintStream out) {


		if (data == null) {
			out.println("NO CPLC");
			return;
		}
		CPLC cplc = new CPLC(data);
		out.println(cplc);
	}


	// TODO public for debuggin purposes
	public static void print_card_info(GlobalPlatform gp) throws CardException, GPException {
		// Print CPLC
		pretty_print_cplc(gp.getCPLC(), System.out);
		// Requires GP?
		// Print CardData
		System.out.println("***** CARD DATA");
		byte [] card_data = gp.fetchCardData();
		pretty_print_card_data(card_data, System.out);
		// Print Key Info Template
		System.out.println("***** KEY INFO");
		pretty_print_key_template(gp.getKeyInfoTemplate(), System.out);
	}

	public static final byte[] defaultKey = { 0x40, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4A, 0x4B, 0x4C, 0x4D, 0x4E, 0x4F };

	public static final class CPLC {

		public enum Field {
			ICFabricator,
			ICType,
			OperatingSystemID,
			OperatingSystemReleaseDate,
			OperatingSystemReleaseLevel,
			ICFabricationDate,
			ICSerialNumber,
			ICBatchIdentifier,
			ICModuleFabricator,
			ICModulePackagingDate,
			ICCManufacturer,
			ICEmbeddingDate,
			ICPrePersonalizer,
			ICPrePersonalizationEquipmentDate,
			ICPrePersonalizationEquipmentID,
			ICPersonalizer,
			ICPersonalizationDate,
			ICPersonalizationEquipmentID
		};
		private HashMap<Field, byte[]> values = null;

		public CPLC(byte [] data) {
			if (data == null) {
				return;
			}
			if (data.length < 3 || data[2] != 0x2A)
				throw new IllegalArgumentException("CPLC must be 0x2A bytes long");
			//offset = TLVUtils.skipTag(data, offset, (short)0x9F7F);
			short offset = 3;
			values = new HashMap<>();
			values.put(Field.ICFabricator, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICType, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.OperatingSystemID, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.OperatingSystemReleaseDate, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.OperatingSystemReleaseLevel, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICFabricationDate, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICSerialNumber, Arrays.copyOfRange(data, offset, offset + 4)); offset += 4;
			values.put(Field.ICBatchIdentifier, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICModuleFabricator, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICModulePackagingDate, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICCManufacturer, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICEmbeddingDate, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICPrePersonalizer, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICPrePersonalizationEquipmentDate, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICPrePersonalizationEquipmentID, Arrays.copyOfRange(data, offset, offset + 4)); offset += 4;
			values.put(Field.ICPersonalizer, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICPersonalizationDate, Arrays.copyOfRange(data, offset, offset + 2)); offset += 2;
			values.put(Field.ICPersonalizationEquipmentID, Arrays.copyOfRange(data, offset, offset + 4)); offset += 4;
		}

		public String toString() {
			String s = "Card CPLC:";
			for (Field f: Field.values()) {
				s += "\n" + f.name() + ": " + HexUtils.encodeHexString(values.get(f));
			}
			return s;
		}
	}
}
