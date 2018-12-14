/*
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

package com.zhihu.android.sugaradapterdemo.holder;

import android.support.annotation.NonNull;
import android.view.View;
import com.zhihu.android.sugaradapterdemo.item.Foo;

// Just extends FooHolder with same data,
// so you need to use SugarAdapter#addDispatcher
public final class FooHolder2 extends FooHolder {
    public FooHolder2(@NonNull View view) {
        super(view);
    }

    @Override
    protected void onBindData(@NonNull Foo foo) {
        String text = foo.getText() + " - FooHolder2";
        mTextView.setText(text);
    }
}
