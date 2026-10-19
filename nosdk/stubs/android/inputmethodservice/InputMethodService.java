package android.inputmethodservice;
import android.app.Dialog;
import android.view.inputmethod.InputConnection;
// Real hierarchy: AbstractInputMethodService extends WindowProviderService extends Service; not needed here
public class InputMethodService {
    public Dialog getWindow() { throw new RuntimeException("Stub!"); }
    public InputConnection getCurrentInputConnection() { throw new RuntimeException("Stub!"); }
    public void sendDownUpKeyEvents(int keyEventCode) { throw new RuntimeException("Stub!"); }
}
