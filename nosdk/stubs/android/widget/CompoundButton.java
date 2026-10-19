package android.widget;
import android.content.Context;
public abstract class CompoundButton extends Button {
    public CompoundButton(Context context) { super(context); throw new RuntimeException("Stub!"); }
    public void setChecked(boolean checked) { throw new RuntimeException("Stub!"); }
    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) { throw new RuntimeException("Stub!"); }
    public interface OnCheckedChangeListener {
        void onCheckedChanged(CompoundButton buttonView, boolean isChecked);
    }
}
