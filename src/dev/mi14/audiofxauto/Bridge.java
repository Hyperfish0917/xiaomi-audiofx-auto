package dev.mi14.audiofxauto;

import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;

/** Small, explicit Binder protocol. No command strings or arbitrary shell execution API. */
public final class Bridge {
    public static final String DESCRIPTOR = "dev.mi14.audiofxauto.IRunner";
    public static final int RUN = IBinder.FIRST_CALL_TRANSACTION;
    public static final int VERIFY = IBinder.FIRST_CALL_TRANSACTION + 2;
    public static final int CANCEL = IBinder.FIRST_CALL_TRANSACTION + 1;
    public static final int PAGE_SYNC = IBinder.FIRST_CALL_TRANSACTION + 3;
    public static final int CANCEL_PAGE = IBinder.FIRST_CALL_TRANSACTION + 4;
    public static final int DESTROY = 16777115;
    private Bridge() {}
    public static String call(IBinder binder, int code) throws RemoteException {
        return call(binder,code,null);
    }
    public static String call(IBinder binder, int code, String config) throws RemoteException {
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            if(code==RUN||code==VERIFY||code==PAGE_SYNC)data.writeString(config);
            if (!binder.transact(code, data, reply, 0)) throw new RemoteException("Unknown runner transaction");
            reply.readException();
            return reply.readString();
        } finally { data.recycle(); reply.recycle(); }
    }
}
