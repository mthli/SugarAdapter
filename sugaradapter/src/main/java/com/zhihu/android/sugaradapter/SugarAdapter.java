/*
 * Copyright 2020 Matthew Lee
 * Copyright 2018 Zhihu Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.zhihu.android.sugaradapter;

import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.IntRange;
import androidx.annotation.LayoutRes;
import androidx.annotation.MainThread;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.RecyclerView;

import java.lang.reflect.ParameterizedType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@SuppressWarnings({"rawtypes", "unused", "WeakerAccess"})
public final class SugarAdapter extends RecyclerView.Adapter<SugarHolder> {
    private static final String TAG = "SugarAdapter";

    public static final class Builder {
        private List<?> mList;
        private SparseArray<Container> mContainerArray;
        private boolean mPreInflate;

        @NonNull
        public static Builder with(@NonNull List<?> list) {
            return new Builder(list);
        }

        private Builder(@NonNull List<?> list) {
            mList = list;
            mContainerArray = new SparseArray<>();
        }

        @NonNull
        public <SH extends SugarHolder> Builder add(@NonNull Class<SH> holderClass) {
            return add(holderClass, null);
        }

        @NonNull
        public <SH extends SugarHolder> Builder add(
                @NonNull Class<SH> holderClass, @Nullable SugarHolder.OnCreatedCallback<SH> callback) {
            ContainerDelegate delegate = Sugar.INSTANCE.getContainerDelegate();
            Class dataClass = delegate.getDataClass(holderClass);
            int layoutRes = delegate.getLayoutRes(holderClass);

            if (layoutRes == 0) {
                throw new IllegalStateException(holderClass.getCanonicalName()
                        + " must have an annotation @Layout(R.layout.*)");
            }

            mContainerArray.put(holderClass.hashCode(), new Container(holderClass, dataClass, layoutRes, callback));
            return this;
        }

        @NonNull
        public <SH extends SugarHolder> Builder preInflate(boolean enable) {
            mPreInflate = enable;
            return this;
        }

        @NonNull
        public SugarAdapter build() {
            if (mContainerArray.size() <= 0) {
                throw new IllegalStateException("must add at least one Class<? extends SugarHolder>");
            }

            return new SugarAdapter(mList, mContainerArray, mPreInflate);
        }
    }

    public interface ExtraDelegate {
        void onAttachedToRecyclerView(@NonNull RecyclerView view);
        void onDetachedFromRecyclerView(@NonNull RecyclerView view);
    }

    public interface PreInflateListener {
        @WorkerThread
        void onPreInflateExecuted(@LayoutRes int layoutRes);
        @MainThread
        void onPreInflateConsumed(@LayoutRes int layoutRes, boolean fallback);
    }

    public static abstract class Dispatcher<T> {
        // return null to use default rule
        @Nullable
        public abstract Class<? extends SugarHolder> dispatch(@NonNull T data);

        // https://stackoverflow.com/q/3437897
        @Deprecated
        @NonNull
        private Class<T> ofType() {
            try {
                // noinspection ConstantConditions
                String className = ((ParameterizedType) getClass().getGenericSuperclass())
                        .getActualTypeArguments()[0].toString().split(" ")[1];
                // noinspection unchecked
                return (Class<T>) Class.forName(className);
            } catch (@NonNull Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

    public static abstract class SugarHolderListener<SH extends SugarHolder> {
        private Class<SH> mSugarHolderClass = ofType();

        // https://stackoverflow.com/q/3437897
        @NonNull
        private Class<SH> ofType() {
            try {
                // noinspection ConstantConditions
                String className = ((ParameterizedType) getClass().getGenericSuperclass())
                        .getActualTypeArguments()[0].toString().split(" ")[1];
                // noinspection unchecked
                return (Class<SH>) Class.forName(className);
            } catch (@NonNull Exception e) {
                throw new RuntimeException(e);
            }
        }

        private boolean isInstance(@Nullable Object object) {
            return mSugarHolderClass.isInstance(object);
        }

        public void onSugarHolderCreated(@NonNull SH holder) {}
        public void onSugarHolderBindData(@NonNull SH holder) {}
        public void onSugarHolderViewAttachedToWindow(@NonNull SH holder) {}
        public void onSugarHolderViewDetachedFromWindow(@NonNull SH holder) {}
        public void onSugarHolderViewRecycled(@NonNull SH holder) {}
    }

    private List<?> mList;
    private SparseArray<Container> mContainerArray;
    private Map<Class<?>, Dispatcher<?>> mDispatcherMap;
    private List<ExtraDelegate> mExtraDelegateList;
    private List<PreInflateListener> mPreInflateListenerList;
    private List<SugarHolderListener<?>> mSugarHolderListenerList;

    private SparseArray<AtomicReference<View>> mPreInflateArray;
    private PreInflateThread mPreInflateThread;
    private LayoutInflater mInflater;

    private SugarAdapter(@NonNull List<?> list, @NonNull SparseArray<Container> containerArray, boolean preInflate) {
        mList = list;
        mContainerArray = containerArray;
        mDispatcherMap = new HashMap<>();
        mExtraDelegateList = new ArrayList<>();
        mPreInflateListenerList = new ArrayList<>();
        mSugarHolderListenerList = new ArrayList<>();

        if (!preInflate) return;
        mPreInflateArray = new SparseArray<>();

        for (int i = 0; i < mContainerArray.size(); i++) {
            int key = mContainerArray.keyAt(i);
            Container container = mContainerArray.get(key);
            mPreInflateArray.put(container.getLayoutRes(), null);
        }
    }

    // <editor-fold desc="Dispatcher">

    @Deprecated
    @NonNull
    public <T> SugarAdapter addDispatcher(@NonNull Dispatcher<T> dispatcher) {
        return addDispatcher(dispatcher.ofType(), dispatcher);
    }

    @NonNull
    public <T> SugarAdapter addDispatcher(@NonNull Class<T> clazz, @NonNull Dispatcher<T> dispatcher) {
        if (mDispatcherMap.containsKey(clazz)) {
            Log.d(TAG, "addDispatcher repeated"
                    + ", SugarAdapter already has a dispatcher of " + clazz.getCanonicalName()
                    + ", new dispatcher will cover the old one.");
        }

        mDispatcherMap.put(clazz, dispatcher);
        return this;
    }

    @Deprecated
    @NonNull
    public <T> SugarAdapter removeDispatcher(@NonNull Dispatcher<T> dispatcher) {
        return removeDispatcher(dispatcher.ofType());
    }

    @NonNull
    public <T> SugarAdapter removeDispatcher(@NonNull Class<T> clazz) {
        mDispatcherMap.remove(clazz);
        return this;
    }

    @NonNull
    public SugarAdapter clearDispatcher() {
        mDispatcherMap.clear();
        return this;
    }

    // </editor-fold>

    // <editor-fold desc="ExtraDelegate">

    @NonNull
    public SugarAdapter addExtraDelegate(@NonNull ExtraDelegate delegate) {
        if (!mExtraDelegateList.contains(delegate)) {
            mExtraDelegateList.add(delegate);
        }

        return this;
    }

    @NonNull
    public SugarAdapter removeExtraDelegate(@NonNull ExtraDelegate delegate) {
        mExtraDelegateList.remove(delegate);
        return this;
    }

    @NonNull
    public SugarAdapter clearExtraDelegate() {
        mExtraDelegateList.clear();
        return this;
    }

    // </editor-fold>

    // <editor-fold desc="PreInflateListener">

    @NonNull
    public SugarAdapter addPreInflateListener(@NonNull PreInflateListener listener) {
        if (!mPreInflateListenerList.contains(listener)) {
            mPreInflateListenerList.add(listener);
        }

        return this;
    }

    @NonNull
    public SugarAdapter removePreInflateListener(@NonNull PreInflateListener listener) {
        mPreInflateListenerList.remove(listener);
        return this;
    }

    @SuppressWarnings("UnusedReturnValue")
    @NonNull
    public SugarAdapter clearPreInflateListener() {
        mPreInflateListenerList.clear();
        return this;
    }

    @NonNull
    public SugarAdapter clearPreInflateViews() {
        clearPreInflateListener();

        if (mPreInflateThread != null) {
            mPreInflateThread.clear();
            mPreInflateThread.interrupt();
            mPreInflateThread = null;
        }

        mPreInflateArray = null;
        return this;
    }

    // </editor-fold>

    // <editor-fold desc="SugarHolderListener">

    @SuppressWarnings("UnusedReturnValue")
    @NonNull
    public <SH extends SugarHolder> SugarAdapter addSugarHolderListener(@NonNull SugarHolderListener<SH> listener) {
        if (!mSugarHolderListenerList.contains(listener)) {
            mSugarHolderListenerList.add(listener);
        }

        return this;
    }

    @NonNull
    public SugarAdapter removeSugarHolderListener(@NonNull SugarHolderListener<?> listener) {
        mSugarHolderListenerList.remove(listener);
        return this;
    }

    @NonNull
    public SugarAdapter clearSugarHolderListener() {
        mSugarHolderListenerList.clear();
        return this;
    }

    // </editor-fold>

    @NonNull
    public List<?> getList() {
        return mList;
    }

    @Override
    public int getItemCount() {
        return mList.size();
    }

    @SuppressWarnings({"ConstantConditions", "unchecked"})
    @Override
    public int getItemViewType(@IntRange(from = 0) int position) {
        Object data = mList.get(position);

        Class<? extends SugarHolder> holderClass = null;
        if (mDispatcherMap.containsKey(data.getClass())) {
            Dispatcher dispatcher = mDispatcherMap.get(data.getClass());
            holderClass = dispatcher.dispatch(data);
        }

        if (holderClass != null) {
            int key = holderClass.hashCode();
            if (mContainerArray.indexOfKey(key) < 0) {
                throw new RuntimeException("getItemViewType() failed, holder: " + holderClass.getCanonicalName()
                        + ", please make sure you have added it when build SugarAdapter.");
            }

            mContainerArray.get(key).setData(data);
            return key;
        }

        for (int i = 0; i < mContainerArray.size(); i++) {
            int key = mContainerArray.keyAt(i);
            Container container = mContainerArray.get(key);
            if (container.getDataClass() == data.getClass()) {
                container.setData(data);
                return key;
            }
        }

        throw new RuntimeException("getItemViewType() failed, data: " + data.getClass().getCanonicalName()
                + ", please make sure you have associated it with a Class<? extends SugarHolder>");
    }

    @SuppressWarnings("unchecked")
    @Override
    @NonNull
    public SugarHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        Container container = mContainerArray.get(viewType);

        try {
            View view = null;
            int layoutRes = container.getLayoutRes();

            if (mPreInflateArray != null) {
                AtomicReference<View> viewRef = mPreInflateArray.get(layoutRes);
                if (viewRef != null) {
                    view = viewRef.getAndSet(null);
                }

                if (mPreInflateThread != null) {
                    mPreInflateThread.inflate(layoutRes);
                }

                for (PreInflateListener listener : mPreInflateListenerList) {
                    if (listener != null) {
                        listener.onPreInflateConsumed(layoutRes, view == null);
                    }
                }
            }

            if (view == null) {
                if (mInflater == null) {
                    mInflater = LayoutInflater.from(parent.getContext());
                }
                view = mInflater.inflate(layoutRes, parent, false);
            }

            SugarHolder holder = container.getHolderClass().getDeclaredConstructor(View.class).newInstance(view);
            holder.setAdapter(this);
            holder.setData(container.getData()); // makes SugarHolder#getData non-null

            SugarHolder.OnCreatedCallback callback = container.getCallback();
            if (callback != null) {
                callback.onCreated(holder);
            }

            holder.getLifecycleRegistry().handleLifecycleEvent(Lifecycle.Event.ON_CREATE);
            for (SugarHolderListener listener : mSugarHolderListenerList) {
                if (listener.isInstance(holder)) {
                    listener.onSugarHolderCreated(holder);
                }
            }

            return holder;
        } catch (@NonNull Exception e) {
            Log.e(TAG, "onCreateViewHolder failed, holder: " + container.getHolderClass().getCanonicalName());
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull SugarHolder holder, int position, @NonNull List<Object> payloads) {
        onBindViewHolderInternal(holder, position, payloads);
    }

    @Override
    public void onBindViewHolder(@NonNull SugarHolder holder, int position) {
        onBindViewHolderInternal(holder, position, null);
    }

    @SuppressWarnings("unchecked")
    private void onBindViewHolderInternal(@NonNull SugarHolder holder, int position, @Nullable List<Object> payloads) {
        Object data = mList.get(position);
        holder.setData(data); // double check

        if (payloads == null || payloads.isEmpty()) {
            holder.onBindData(data, Collections.emptyList());
        } else {
            holder.onBindData(data, payloads);
        }

        holder.getLifecycleRegistry().handleLifecycleEvent(Lifecycle.Event.ON_START);

        for (SugarHolderListener listener : mSugarHolderListenerList) {
            if (listener.isInstance(holder)) {
                listener.onSugarHolderBindData(holder);
            }
        }
    }

    @Override
    public void onViewAttachedToWindow(@NonNull SugarHolder holder) {
        // holder.onViewAttachedToWindow();
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull SugarHolder holder) {
        // holder.onViewDetachedFromWindow();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void onViewRecycled(@NonNull SugarHolder holder) {
        holder.onViewRecycled();
        holder.getLifecycleRegistry().handleLifecycleEvent(Lifecycle.Event.ON_DESTROY);

        for (SugarHolderListener listener : mSugarHolderListenerList) {
            if (listener.isInstance(holder)) {
                listener.onSugarHolderViewRecycled(holder);
            }
        }
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView view) {
        for (ExtraDelegate delegate : mExtraDelegateList) {
            if (delegate != null) {
                delegate.onAttachedToRecyclerView(view);
            }
        }

        if (mPreInflateThread == null && mPreInflateArray != null) {
            mPreInflateThread = new PreInflateThread(view, mPreInflateArray, mPreInflateListenerList);
            mPreInflateThread.start();
        }
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView view) {
        for (ExtraDelegate delegate : mExtraDelegateList) {
            if (delegate != null) {
                delegate.onDetachedFromRecyclerView(view);
            }
        }

        if (mPreInflateThread != null) {
            mPreInflateThread.interrupt();
            mPreInflateThread = null;
        }
    }

    @SuppressWarnings("unchecked")
    protected void onSugarHolderViewAttachedToWindow(@NonNull SugarHolder holder) {
        for (SugarHolderListener listener : mSugarHolderListenerList) {
            if (listener.isInstance(holder)) {
                listener.onSugarHolderViewAttachedToWindow(holder);
            }
        }
    }

    @SuppressWarnings("unchecked")
    protected void onSugarHolderViewDetachedFromWindow(@NonNull SugarHolder holder) {
        for (SugarHolderListener listener : mSugarHolderListenerList) {
            if (listener.isInstance(holder)) {
                listener.onSugarHolderViewDetachedFromWindow(holder);
            }
        }
    }

    @Override
    public boolean onFailedToRecycleView(@NonNull SugarHolder holder) {
        return holder.onFailedToRecycleView();
    }
}
