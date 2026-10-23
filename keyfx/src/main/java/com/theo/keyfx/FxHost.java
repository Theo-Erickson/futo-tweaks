package com.theo.keyfx;

/**
 * What keyfx asks of a host that draws more than an overlay. Hosts pull state from KeyFx
 * (look(), word(), popupLayout(), popupLook()); these calls say when it changed. keyfx calls
 * them on the UI thread, from its own event methods or draw().
 */
public interface FxHost {
    /**
     * What look() returns for some key changed; redraw the real keys. May be called from inside
     * the overlay's draw(), so hosts should only invalidate, never draw synchronously.
     */
    void redrawKeys();

    /** What word() returns changed; restyle the suggestion bar. */
    void wordChanged();
}
