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
package com.addthis.codec;

public interface Codec {

    public byte[] encode(Object obj) throws Exception;

    public <T> T decode(T shell, byte[] data) throws Exception;

    public <T> T decode(Class<T> type, byte[] data) throws Exception;

    public boolean storesNull(byte[] data);
}
