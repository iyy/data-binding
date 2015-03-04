/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.databinding.library;

import android.binding.Observable;
import android.binding.ObservableList;
import android.binding.ObservableMap;
import android.binding.OnListChangedListener;
import android.binding.OnMapChangedListener;
import android.binding.OnPropertyChangedListener;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;

import java.lang.Override;
import java.lang.Runnable;
import java.lang.ref.WeakReference;

abstract public class ViewDataBinder {
    public static final String BINDING_TAG_PREFIX = "bindingTag";
    private static final int BINDING_NUMBER_START = BINDING_TAG_PREFIX.length();

    private static final CreateWeakListener CREATE_PROPERTY_LISTENER = new CreateWeakListener() {
        @Override
        public WeakListener create(ViewDataBinder viewDataBinder, int localFieldId) {
            return new WeakPropertyListener(viewDataBinder, localFieldId);
        }
    };

    private static final CreateWeakListener CREATE_LIST_LISTENER = new CreateWeakListener() {
        @Override
        public WeakListener create(ViewDataBinder viewDataBinder, int localFieldId) {
            return new WeakListListener(viewDataBinder, localFieldId);
        }
    };

    private static final CreateWeakListener CREATE_MAP_LISTENER = new CreateWeakListener() {
        @Override
        public WeakListener create(ViewDataBinder viewDataBinder, int localFieldId) {
            return new WeakMapListener(viewDataBinder, localFieldId);
        }
    };

    WeakListener[] mLocalFieldObservers;
    protected abstract boolean onFieldChange(int mLocalFieldId, Object object, int fieldId);
    public abstract boolean setVariable(int variableId, Object variable);
    public abstract void rebindDirty();
    private final View mRoot;

    private boolean mPendingRebind = false;
    private Runnable mRebindRunnable = new Runnable() {
        @Override
        public void run() {
            rebindDirty();
            mPendingRebind = false;
        }
    };

    public ViewDataBinder(View root, int localFieldCount) {
        mLocalFieldObservers = new WeakListener[localFieldCount];
        mRoot = root;
        mRoot.setTag(this);
    }

    @Override
    protected void finalize() throws Throwable {
        for (WeakListener weakListener : mLocalFieldObservers) {
            if (weakListener != null) {
                weakListener.unregister();
            }
        }
    }

    public View getRoot() {
        return mRoot;
    }

    private void handleFieldChange(int mLocalFieldId, Object object, int fieldId) {
        boolean result = onFieldChange(mLocalFieldId, object, fieldId);
        if (result) {
            requestRebind();
        }
    }

    protected boolean unregisterFrom(int localFieldId) {
        WeakListener listener = mLocalFieldObservers[localFieldId];
        if (listener != null) {
            return listener.unregister();
        }
        return false;
    }

    protected void requestRebind() {
        if (mPendingRebind) {
            return;
        }
        mPendingRebind = true;
        mRoot.postOnAnimation(mRebindRunnable);
    }

    protected Object getObservedField(int localFieldId) {
        WeakListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            return null;
        }
        return listener.getTarget();
    }

    private boolean updateRegistration(int localFieldId, Object observable,
            CreateWeakListener listenerCreator) {
        if (observable == null) {
            return unregisterFrom(localFieldId);
        }
        WeakListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            registerTo(localFieldId, observable, listenerCreator);
            return true;
        }
        if (listener.getTarget() == observable) {
            return false;//nothing to do, same object
        }
        unregisterFrom(localFieldId);
        registerTo(localFieldId, observable, listenerCreator);
        return true;
    }

    protected boolean updateRegistration(int localFieldId, Observable observable) {
        return updateRegistration(localFieldId, observable, CREATE_PROPERTY_LISTENER);
    }

    protected boolean updateRegistration(int localFieldId, ObservableList observable) {
        return updateRegistration(localFieldId, observable, CREATE_LIST_LISTENER);
    }

    protected boolean updateRegistration(int localFieldId, ObservableMap observable) {
        return updateRegistration(localFieldId, observable, CREATE_MAP_LISTENER);
    }

    protected void registerTo(int localFieldId, Object observable,
            CreateWeakListener listenerCreator) {
        if (observable == null) {
            return;
        }
        WeakListener listener = mLocalFieldObservers[localFieldId];
        if (listener == null) {
            listener = listenerCreator.create(this, localFieldId);
            mLocalFieldObservers[localFieldId] = listener;
        }
        listener.setTarget(observable);
    }

    protected static void mapTaggedChildViews(View root, View[] views,
            SparseArray<View> binders) {
        if (root instanceof ViewGroup) {
            ViewGroup viewGroup = (ViewGroup) root;
            for (int i = viewGroup.getChildCount() - 1; i >= 0; i--) {
                mapTaggedViews(viewGroup.getChildAt(i), views, binders);
            }
        }
    }

    private static void mapTaggedViews(View view, View[] views, SparseArray<View> binders) {
        String tag = (String) view.getTag();
        System.out.println("tagged with " + tag + " is " + view);
        if (tag != null && tag.startsWith(BINDING_TAG_PREFIX)) {
            int tagIndex = parseTagInt(tag);
            System.out.println("tag id is " + tagIndex);
            views[tagIndex] = view;
        } else if (view.getId() > 0 && binders != null) {
            binders.put(view.getId(), view);
        }
        mapTaggedChildViews(view, views, binders);
    }

    /**
     * Parse the tag without creating a new String object. This is fast and assumes the
     * tag is in the correct format.
     * @param str The tag string.
     * @return The binding tag number parsed from the tag string.
     */
    private static int parseTagInt(String str) {
        final int end = str.length();
        int val = 0;
        for (int i = BINDING_NUMBER_START; i < end; i++) {
            val *= 10;
            char c = str.charAt(i);
            val += (c - '0');
        }
        return val;
    }

    protected static abstract class WeakListener<T> {
        private final WeakReference<ViewDataBinder> mBinder;
        protected final int mLocalFieldId;
        private T mTarget;

        public WeakListener(ViewDataBinder binder, int localFieldId) {
            mBinder = new WeakReference<ViewDataBinder>(binder);
            mLocalFieldId = localFieldId;
        }

        public void setTarget(T object) {
            unregister();
            mTarget = object;
            if (mTarget != null) {
                addListener(mTarget);
            }
        }

        public boolean unregister() {
            boolean unregistered = false;
            if (mTarget != null) {
                removeListener(mTarget);
                unregistered = true;
            }
            mTarget = null;
            return unregistered;
        }

        public T getTarget() {
            return mTarget;
        }

        protected ViewDataBinder getBinder() {
            ViewDataBinder binder = mBinder.get();
            if (binder == null) {
                unregister(); // The binder is dead
            }
            return binder;
        }

        protected abstract void addListener(T target);
        protected abstract void removeListener(T target);
    }

    protected static class WeakPropertyListener extends WeakListener<Observable>
            implements OnPropertyChangedListener {
        public WeakPropertyListener(ViewDataBinder binder, int localFieldId) {
            super(binder, localFieldId);
        }

        @Override
        protected void addListener(Observable target) {
            target.addOnPropertyChangedListener(this);
        }

        @Override
        protected void removeListener(Observable target) {
            target.removeOnPropertyChangedListener(this);
        }

        @Override
        public void onPropertyChanged(Observable sender, int fieldId) {
            ViewDataBinder binder = getBinder();
            if (binder == null) {
                return;
            }
            Observable obj = getTarget();
            if (obj != sender) {
                return; // notification from the wrong object?
            }
            binder.handleFieldChange(mLocalFieldId, sender, fieldId);
        }
    }

    protected static class WeakListListener extends WeakListener<ObservableList>
            implements OnListChangedListener {

        public WeakListListener(ViewDataBinder binder, int localFieldId) {
            super(binder, localFieldId);
        }

        @Override
        public void onChanged() {
            ViewDataBinder binder = getBinder();
            if (binder == null) {
                return;
            }
            ObservableList target = getTarget();
            if (target == null) {
                return; // We don't expect any notifications from null targets
            }
            binder.handleFieldChange(mLocalFieldId, target, 0);
        }

        @Override
        public void onItemRangeChanged(int positionStart, int itemCount) {
            onChanged();
        }

        @Override
        public void onItemRangeInserted(int positionStart, int itemCount) {
            onChanged();
        }

        @Override
        public void onItemRangeMoved(int fromPosition, int toPosition, int itemCount) {
            onChanged();
        }

        @Override
        public void onItemRangeRemoved(int positionStart, int itemCount) {
            onChanged();
        }

        @Override
        protected void addListener(ObservableList target) {
            target.addOnListChangedListener(this);
        }

        @Override
        protected void removeListener(ObservableList target) {
            target.removeOnListChangedListener(this);
        }
    }

    protected static class WeakMapListener extends WeakListener<ObservableMap>
            implements OnMapChangedListener {
        public WeakMapListener(ViewDataBinder binder, int localFieldId) {
            super(binder, localFieldId);
        }

        @Override
        protected void addListener(ObservableMap target) {
            target.addOnMapChangedListener(this);
        }

        @Override
        protected void removeListener(ObservableMap target) {
            target.removeOnMapChangedListener(this);
        }

        @Override
        public void onMapChanged(ObservableMap sender, Object key) {
            ViewDataBinder binder = getBinder();
            if (binder == null || sender != getTarget()) {
                return;
            }
            binder.handleFieldChange(mLocalFieldId, sender, 0);
        }
    }

    private interface CreateWeakListener {
        WeakListener create(ViewDataBinder viewDataBinder, int localFieldId);
    }
}
