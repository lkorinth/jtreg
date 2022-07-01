/*
 * Copyright (c) 2022, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

package com.sun.javatest.regtest.exec;

import static java.nio.file.Path.of;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

public class Cgroup {
    private static Stream<String> lines(Path path) {
        try {
            return Files.lines(path, Charset.forName("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private static void write(Path path, String value) {
        try {
            Files.writeString(path, value, Charset.forName("UTF-8"));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    //9:memory:/user.slice/user-1001.slice/session-3.scope -> /sys/fs/cgroup/ memory /user.slice/user-1001.slice/session-3.scope 
    //0::/user.slice/user-1000.slice/session-3.scope -> /sys/fs/cgroup/ /user.slice/user-1000.slice/session-3.scope
    static String groupLocation(String cgroupLine) {
        return "/sys/fs/cgroup/" + cgroupLine.split(":")[1] + cgroupLine.split(":")[2];
    }

    static Optional<String> getCgroupByPid(long pid) {
        return lines(of("/proc/" + pid + "/cgroup"))
            .filter(str -> str.charAt(0) == '0' || str.matches("[0-9]+:memory:.*")) // "v1 memory" or "v2"
            .findAny()
            .map(Cgroup::groupLocation); 
    }
    
    public static Optional<String> getMyCgroup() {
        return getCgroupByPid(ProcessHandle.current().pid());
    }

    public static void main(String[] args) {
        getMyCgroup().ifPresent(group -> {
            Path jtreg = of(group + "/jtreg");
            try {
                System.out.println("lkorinth creating directory: " + jtreg);
                Files.createDirectories(jtreg);
                Files.createTempDirectory(jtreg, "jtreg");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
