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

package com.zhihu.android.sugaradapterlibrary.holder

import android.view.View
import androidx.appcompat.widget.AppCompatTextView
import com.zhihu.android.sugaradapter.Id
import com.zhihu.android.sugaradapter.Layout
import com.zhihu.android.sugaradapter.SugarHolder
import com.zhihu.android.sugaradapterlibrary.R2
import com.zhihu.android.sugaradapterlibrary.item.LibraryItem

@Layout(R2.layout.layout_library)
class LibraryHolder(view: View) : SugarHolder<LibraryItem>(view) {
    @Id(R2.id.text)
    lateinit var textView: AppCompatTextView

    override fun onBindData(item: LibraryItem) {
        textView.text = item.text
    }
}
