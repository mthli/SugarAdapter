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

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

class PreInflateThread extends Thread {
    private static final int MESSAGE_INTERRUPT = 0x00;
    private static final int MESSAGE_INFLATE = 0x01;
    private static final int MESSAGE_CLEAR = 0x02;

    private SparseArray<AtomicReference<View>> mViewRefArray;
    private List<SugarAdapter.PreInflateListener> mListenerList;
    private ViewGroup mParent;
    private Handler mHandler;
    private LayoutInflater mInflater;

    public PreInflateThread(
            @NonNull ViewGroup parent,
            @NonNull SparseArray<AtomicReference<View>> viewRefArray,
            @NonNull List<SugarAdapter.PreInflateListener> listenerList
    ) {
        mViewRefArray = viewRefArray;
        mListenerList = listenerList;
        mParent = parent;
        mInflater = LayoutInflater.from(parent.getContext());
    }

    @Override
    public void run() {
        Looper.prepare();

        Thread thread = Thread.currentThread();
        for (int i = 0; i < mViewRefArray.size() && !thread.isInterrupted(); i++) {
            inflateView(mViewRefArray.keyAt(i));
        }

        if (thread.isInterrupted()) return;
        mHandler = new Handler(msg -> {
            // msg.what = MESSAGE_INTERRUPT
            if (thread.isInterrupted()) {
                Looper looper = Looper.myLooper();
                if (looper != null) {
                    looper.quit();
                }
                return true;
            }

            if (msg.what == MESSAGE_INFLATE) {
                inflateView((int) msg.obj);
            } else if (msg.what == MESSAGE_CLEAR) {
                mViewRefArray.clear();
            }

            return true;
        });

        Looper.loop();
    }

    public void clear() {
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MESSAGE_CLEAR);
        }
    }

    public void inflate(@LayoutRes int layoutRes) {
        if (mHandler != null) {
            Message message = mHandler.obtainMessage(MESSAGE_INFLATE, layoutRes);
            mHandler.sendMessage(message);
        }
    }

    @Override
    public void interrupt() {
        super.interrupt();
        if (mHandler != null) {
            mHandler.sendEmptyMessage(MESSAGE_INTERRUPT);
        }
    }

    private void inflateView(@LayoutRes int layoutRes) {
        View view = mInflater.inflate(layoutRes, mParent, false);
        AtomicReference<View> viewRef = mViewRefArray.get(layoutRes);
        if (viewRef != null) {
            viewRef.set(view);
        } else {
            mViewRefArray.put(layoutRes, new AtomicReference<>(view));
        }

        for (SugarAdapter.PreInflateListener listener : mListenerList) {
            if (listener != null) {
                listener.onPreInflateExecuted(layoutRes);
            }
        }
    }
}
