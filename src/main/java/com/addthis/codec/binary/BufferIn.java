/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.addthis.codec.binary;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import java.util.Stack;

import com.addthis.basis.util.Bytes;

final class BufferIn {

    ByteArrayInputStream        in;
    Stack<ByteArrayInputStream> stack;

    BufferIn(final byte[] data) throws IOException {
        stack = new Stack<ByteArrayInputStream>();
        in = new ByteArrayInputStream(data);
    }

    public void push() throws IOException {
        int len = (int) Bytes.readLength(in);
        byte[] ndat = Bytes.readBytes(in, len);
        stack.push(in);
        in = new ByteArrayInputStream(ndat);
    }

    public void pop() {
        in = stack.pop();
    }

    @Override
    public String toString() {
        return "BufferIn:" + (in != null ? in.available() : -1);
    }
}