package dev.mbaiforinstinct.rebornlauncher.data;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.telephony.SmsManager;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class PhoneStore {

    public static final class Sms {
        public String address;
        public String body;
        public long date;
        public int type;
        public boolean unread;
        public String dateLabel() {
            return new SimpleDateFormat("dd/MM HH:mm", Locale.UK).format(new Date(date));
        }
        public String directionLabel() {
            return type == 2 ? "Sent" : "Received";
        }
    }

    public static List<Sms> sms(Context c, int limit) {
        List<Sms> out = new ArrayList<>();
        // All types, not just the inbox: sent messages (type 2) belong in the
        // conversation list too (S40 conversations show both directions).
        Uri uri = Uri.parse("content://sms");
        String[] cols = {"address", "body", "date", "type", "read"};
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, "date DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < limit) {
                Sms m = new Sms();
                m.address = cur.getString(0);
                m.body = cur.getString(1);
                m.date = cur.getLong(2);
                m.type = cur.getInt(3);
                m.unread = cur.getInt(4) == 0;
                out.add(m);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static int unreadSms(Context c) {
        Uri uri = Uri.parse("content://sms/inbox");
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{"_id"}, "read = 0", null, null)) {
            return cur == null ? 0 : cur.getCount();
        } catch (Exception e) {
            return 0;
        }
    }

    public static int missedCalls(Context c) {
        String sel = CallLog.Calls.TYPE + " = " + CallLog.Calls.MISSED_TYPE + " AND " + CallLog.Calls.NEW + " = 1";
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, new String[]{CallLog.Calls._ID}, sel, null, null)) {
            return cur == null ? 0 : cur.getCount();
        } catch (Exception e) {
            return 0;
        }
    }

    public static List<String[]> callLog(Context c, int limit) {
        List<String[]> out = new ArrayList<>();
        String[] cols = {CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE};
        try (Cursor cur = c.getContentResolver().query(CallLog.Calls.CONTENT_URI, cols, null, null, CallLog.Calls.DATE + " DESC")) {
            while (cur != null && cur.moveToNext() && out.size() < limit) {
                String number = cur.getString(0);
                String name = cur.getString(1);
                int type = cur.getInt(2);
                long date = cur.getLong(3);
                String typeLabel = type == CallLog.Calls.MISSED_TYPE ? "Missed"
                        : type == CallLog.Calls.OUTGOING_TYPE ? "Outgoing" : "Incoming";
                String when = new SimpleDateFormat("dd/MM HH:mm", Locale.UK).format(new Date(date));
                out.add(new String[]{name != null ? name : number, typeLabel, when, number});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static List<String[]> contacts(Context c, int limit) {
        List<String[]> out = new ArrayList<>();
        Uri uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI;
        String[] cols = {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        String order = ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC";
        try (Cursor cur = c.getContentResolver().query(uri, cols, null, null, order)) {
            while (cur != null && cur.moveToNext() && out.size() < limit) {
                out.add(new String[]{cur.getString(0), cur.getString(1)});
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    public static boolean updateContact(Context c, String oldName, String oldNumber, String newName, String newNumber) {
        try {
            long phoneId = -1, rawId = -1;
            String[] cols = {
                    ContactsContract.CommonDataKinds.Phone._ID,
                    ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID
            };
            try (Cursor cur = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=? AND "
                            + ContactsContract.CommonDataKinds.Phone.NUMBER + "=?",
                    new String[]{oldName, oldNumber}, null)) {
                if (cur != null && cur.moveToNext()) {
                    phoneId = cur.getLong(0);
                    rawId = cur.getLong(1);
                }
            }
            if (phoneId < 0) return false;
            java.util.ArrayList<android.content.ContentProviderOperation> ops = new java.util.ArrayList<>();
            ops.add(android.content.ContentProviderOperation.newUpdate(ContactsContract.Data.CONTENT_URI)
                    .withSelection(ContactsContract.Data.RAW_CONTACT_ID + "=? AND "
                                    + ContactsContract.Data.MIMETYPE + "=?",
                            new String[]{String.valueOf(rawId),
                                    ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE})
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, newName)
                    .build());
            ops.add(android.content.ContentProviderOperation.newUpdate(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI)
                    .withSelection(ContactsContract.CommonDataKinds.Phone._ID + "=?",
                            new String[]{String.valueOf(phoneId)})
                    .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, newNumber)
                    .build());
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean deleteContact(Context c, String name, String number) {
        try {
            long rawId = -1;
            String[] cols = {ContactsContract.CommonDataKinds.Phone.RAW_CONTACT_ID};
            try (Cursor cur = c.getContentResolver().query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI, cols,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + "=? AND "
                            + ContactsContract.CommonDataKinds.Phone.NUMBER + "=?",
                    new String[]{name, number}, null)) {
                if (cur != null && cur.moveToNext()) rawId = cur.getLong(0);
            }
            if (rawId < 0) return false;
            c.getContentResolver().delete(ContactsContract.RawContacts.CONTENT_URI,
                    ContactsContract.RawContacts._ID + "=?", new String[]{String.valueOf(rawId)});
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean insertContact(Context c, String name, String number) {
        try {
            java.util.ArrayList<android.content.ContentProviderOperation> ops = new java.util.ArrayList<>();
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.RawContacts.CONTENT_URI)
                    .build());
            ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                    .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                    .withValue(ContactsContract.Data.MIMETYPE,
                            ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                    .withValue(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
                    .build());
            if (number != null && !number.isEmpty()) {
                ops.add(android.content.ContentProviderOperation.newInsert(ContactsContract.Data.CONTENT_URI)
                        .withValueBackReference(ContactsContract.Data.RAW_CONTACT_ID, 0)
                        .withValue(ContactsContract.Data.MIMETYPE,
                                ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                        .withValue(ContactsContract.CommonDataKinds.Phone.NUMBER, number)
                        .withValue(ContactsContract.CommonDataKinds.Phone.TYPE,
                                ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
                        .build());
            }
            c.getContentResolver().applyBatch(ContactsContract.AUTHORITY, ops);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean sendSms(String number, String text) {
        try {
            SmsManager.getDefault().sendTextMessage(number, null, text, null, null);
            android.util.Log.i("Reborn", "sendSms ok to " + number);
            return true;
        } catch (Exception e) {
            android.util.Log.e("Reborn", "sendSms failed to " + number, e);
            return false;
        }
    }
}
