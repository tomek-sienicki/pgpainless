/*
 * Copyright 2021 Paul Schaub.
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
package sop;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public abstract class Ready {

    /**
     * Write the data to the provided output stream.
     *
     * @param outputStream output stream
     * @throws IOException in case of an IO error
     */
    public abstract void writeTo(OutputStream outputStream) throws IOException;

    /**
     * Return the data as a byte array by writing it to a {@link ByteArrayOutputStream} first and then returning
     * the array.
     *
     * @return data as byte array
     * @throws IOException in case of an IO error
     */
    public byte[] getBytes() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        writeTo(bytes);
        return bytes.toByteArray();
    }
}
