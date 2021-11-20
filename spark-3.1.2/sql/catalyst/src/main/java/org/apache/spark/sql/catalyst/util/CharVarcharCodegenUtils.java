/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.util;

import org.apache.spark.unsafe.types.UTF8String;

public class CharVarcharCodegenUtils {
  private static final UTF8String SPACE = UTF8String.fromString(" ");

  private static UTF8String trimTrailingSpaces(
      UTF8String inputStr, int numChars, int limit) {
    // Trailing spaces do not count in the length check. We need to retain the trailing spaces
    // (truncate to length N), as there is no read-time padding for varchar type.
    // TODO: create a special TrimRight function that can trim to a certain length.
    if (inputStr.trimRight().numChars() > limit) {
      throw new RuntimeException("Exceeds char/varchar type length limitation: " + limit);
    } else {
      return inputStr.substring(0, limit);
    }
  }

  public static UTF8String charTypeWriteSideCheck(UTF8String inputStr, int limit) {
    int numChars = inputStr.numChars();
    if (numChars == limit) {
      return inputStr;
    } else if (numChars < limit) {
      return inputStr.rpad(limit, SPACE);
    } else {
      return trimTrailingSpaces(inputStr, numChars, limit);
    }
  }

  public static UTF8String varcharTypeWriteSideCheck(UTF8String inputStr, int limit) {
    int numChars = inputStr.numChars();
    if (numChars <= limit) {
      return inputStr;
    } else {
      return trimTrailingSpaces(inputStr, numChars, limit);
    }
  }
}
