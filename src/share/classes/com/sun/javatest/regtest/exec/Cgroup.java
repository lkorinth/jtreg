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
import static java.math.BigDecimal.valueOf;

import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Predicate;
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
            .filter(str -> str.charAt(0) == '0') // "v2"
            .findAny()
            .map(Cgroup::groupLocation); 
    }
    
    public static Optional<String> getMyCgroup() {
        return getCgroupByPid(ProcessHandle.current().pid());
    }

    public static long getUserId() {
        return 1001;
    }

    public static Optional<String> getUserRootGroup() {
        long id = getUserId();
        String group =  "/sys/fs/cgroup/user.slice/user-" + id + ".slice/user@" + id + ".service";
        return Files.exists(of(group))
            ? Optional.of(group)
            : Optional.empty();
    }

    public static void limit(long bytes) {
        //getMyCgroup().ifPresent(group -> System.out.println("lkorinth inherited cgroup: " + group));
        getUserRootGroup().ifPresent(group -> {
            Path jtreg = of(group + "/jtreg");
            try {
                //System.out.println("lkorinth creating directory: " + jtreg);
                Files.createDirectories(jtreg);
                write(jtreg.resolve("cgroup.subtree_control"), "+memory");
                write(jtreg.resolve("cgroup.subtree_control"), "+pids");
                Path tempGroup = Files.createTempDirectory(jtreg, "jtreg");
                System.out.println("lkorinth creating directory: " + tempGroup.toString() + " with limit: " + bytes);
                write(tempGroup.resolve("memory.max"), "" + bytes);
                write(tempGroup.resolve("memory.swap.max"), "0");
                write(tempGroup.resolve("cgroup.procs"), "0");       
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /*
    record Interval(BigDecimal low, BigDecimal high) {
        BigDecimal midPoint() {
            return low.add(high).divide(valueOf(2));
        }
    };
    */

    static class Interval {
        BigDecimal low, high;
        Interval(BigDecimal low, BigDecimal high) {
            this.low = low;
            this.high = high;
        }
        BigDecimal midPoint() {
            return low.add(high).divide(valueOf(2));
        }
    };
    
    public static Stream<Interval> bisect(Interval interval, Predicate<BigDecimal> predicate) {
        return Stream.iterate(interval, i -> predicate.test(i.midPoint())
                              ? new Interval(i.low, i.midPoint())
                              : new Interval(i.midPoint(), i.high));
    }

    public static boolean canRunProgramWithLimit(ProcessBuilder builder, long memLimit) {
        try {
            limit(memLimit);
            return builder.start().waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }            
    
    public static long bisect(ProcessBuilder builder) {
        return bisect(new Interval(valueOf(0), valueOf(10000000000l)), memLimit -> canRunProgramWithLimit(builder, memLimit.longValue()))
            .skip(10).findFirst().get().midPoint().longValue();
    }
    
    // https://www.kernel.org/doc/html/latest/admin-guide/cgroup-v2.html
    // systemd-run --user --scope /bin/bash  will start with cgroup with correct permissions (user@1000.service)
    public static void main(String[] args) {
        ProcessBuilder builder = new ProcessBuilder("stress", "--vm", "1", "--vm-bytes", "1G", "--timeout", "1");
        System.out.println("lkorinth bisect size: " + bisect(builder));
    }
}
