package me.bmax.apatch.display;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;

public class DisplayBinderContainer implements Parcelable {

    public static final Creator<DisplayBinderContainer> CREATOR = new Creator<DisplayBinderContainer>() {
        @Override
        public DisplayBinderContainer createFromParcel(Parcel in) {
            return new DisplayBinderContainer(in);
        }

        @Override
        public DisplayBinderContainer[] newArray(int size) {
            return new DisplayBinderContainer[size];
        }
    };

    private final IBinder binder;

    public DisplayBinderContainer(IBinder binder) {
        this.binder = binder;
    }

    protected DisplayBinderContainer(Parcel in) {
        this.binder = in.readStrongBinder();
    }

    public IBinder getBinder() {
        return binder;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeStrongBinder(binder);
    }
}
