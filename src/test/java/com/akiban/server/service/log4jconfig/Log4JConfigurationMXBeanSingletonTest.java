/**
 * END USER LICENSE AGREEMENT (“EULA”)
 *
 * READ THIS AGREEMENT CAREFULLY (date: 9/13/2011):
 * http://www.akiban.com/licensing/20110913
 *
 * BY INSTALLING OR USING ALL OR ANY PORTION OF THE SOFTWARE, YOU ARE ACCEPTING
 * ALL OF THE TERMS AND CONDITIONS OF THIS AGREEMENT. YOU AGREE THAT THIS
 * AGREEMENT IS ENFORCEABLE LIKE ANY WRITTEN AGREEMENT SIGNED BY YOU.
 *
 * IF YOU HAVE PAID A LICENSE FEE FOR USE OF THE SOFTWARE AND DO NOT AGREE TO
 * THESE TERMS, YOU MAY RETURN THE SOFTWARE FOR A FULL REFUND PROVIDED YOU (A) DO
 * NOT USE THE SOFTWARE AND (B) RETURN THE SOFTWARE WITHIN THIRTY (30) DAYS OF
 * YOUR INITIAL PURCHASE.
 *
 * IF YOU WISH TO USE THE SOFTWARE AS AN EMPLOYEE, CONTRACTOR, OR AGENT OF A
 * CORPORATION, PARTNERSHIP OR SIMILAR ENTITY, THEN YOU MUST BE AUTHORIZED TO SIGN
 * FOR AND BIND THE ENTITY IN ORDER TO ACCEPT THE TERMS OF THIS AGREEMENT. THE
 * LICENSES GRANTED UNDER THIS AGREEMENT ARE EXPRESSLY CONDITIONED UPON ACCEPTANCE
 * BY SUCH AUTHORIZED PERSONNEL.
 *
 * IF YOU HAVE ENTERED INTO A SEPARATE WRITTEN LICENSE AGREEMENT WITH AKIBAN FOR
 * USE OF THE SOFTWARE, THE TERMS AND CONDITIONS OF SUCH OTHER AGREEMENT SHALL
 * PREVAIL OVER ANY CONFLICTING TERMS OR CONDITIONS IN THIS AGREEMENT.
 */

package com.akiban.server.service.log4jconfig;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

public final class Log4JConfigurationMXBeanSingletonTest {

    private static String sayConfigure(String configFile) {
        return "configure " + configFile;
    }

    private static String sayConfigAndWatch(String configFile, long frequency) {
        return "watch " + frequency + ' ' + configFile;
    }

    private static class TestConfigurator extends Log4JConfigurationMXBeanSingleton {
        private final List<String> messages = new ArrayList<String>();

        @Override
        protected void configure(String configFile) {
            messages.add(sayConfigure(configFile));
        }

        @Override
        protected void configureAndWatch(String configFile, long updateFrequency) {
            messages.add(sayConfigAndWatch(configFile, updateFrequency));
        }

        void assertAll(String expectedConfig, Long expectedUpdate, String... expectedMessages) {
            org.junit.Assert.assertEquals("messages", Arrays.asList(expectedMessages), messages);
            org.junit.Assert.assertEquals("config file", expectedConfig, getConfigurationFile());
            org.junit.Assert.assertEquals("update freq", expectedUpdate, getUpdateFrequencyMS());
        }

        Log4JConfigurationMXBean bean() {
            return this;
        }
    }

    @Test
    public void startsNull() {
        new TestConfigurator().assertAll(null, null);
    }

    @Test
    public void setConfigThenPollThenTryAgain() {
        final TestConfigurator test = new TestConfigurator();
        test.setConfigurationFile("alpha");
        test.assertAll("alpha", null, sayConfigure("alpha"));
        test.bean().pollConfigurationFile("beta", 4);
        test.assertAll("beta", 4L, sayConfigure("alpha"), sayConfigAndWatch("beta", 4));

        expectException(IllegalStateException.class, new Runnable() {
            @Override
            public void run() {
                test.setConfigurationFile("gamma");
            }
        });

        test.assertAll("beta", 4L, sayConfigure("alpha"), sayConfigAndWatch("beta", 4));
    }

    @Test
    public void pollWithoutConfiguring() {
        final TestConfigurator test = new TestConfigurator();
        expectException(IllegalStateException.class, new Runnable() {
            @Override
            public void run() {
                test.bean().pollConfigurationFile(4);
            }
        });
        test.assertAll(null, null);
    }

    @Test
    public void pollNegative() {
        final TestConfigurator test = new TestConfigurator();
        test.setConfigurationFile("alpha");
        expectException(IllegalArgumentException.class, new Runnable() {
            @Override
            public void run() {
                test.bean().pollConfigurationFile(0);
            }
        });
        test.assertAll("alpha", null, sayConfigure("alpha"));
    }

    @Test
    public void nullConfig() {
        final TestConfigurator test = new TestConfigurator();

        expectException(IllegalArgumentException.class, new Runnable() {
            @Override
            public void run() {
                test.bean().setConfigurationFile(null);
            }
        });
        test.assertAll(null, null);
    }

    @Test
    public void nullConfigWithPoll() {
        final TestConfigurator test = new TestConfigurator();

        expectException(IllegalArgumentException.class, new Runnable() {
            @Override
            public void run() {
                test.bean().pollConfigurationFile(null, 4);
            }
        });
        test.assertAll(null, null);
    }

    @Test
    public void configurePollUpdate() {
        final TestConfigurator test = new TestConfigurator();
        final Log4JConfigurationMXBean bean = test.bean();

        bean.setConfigurationFile("alpha");
        test.assertAll("alpha", null, sayConfigure("alpha"));

        bean.pollConfigurationFile("beta", 5);
        test.assertAll("beta", 5L, sayConfigure("alpha"), sayConfigAndWatch("beta", 5));

        bean.updateConfigurationFile();
        test.assertAll("beta", 5L, sayConfigure("alpha"), sayConfigAndWatch("beta", 5), sayConfigure("beta"));
    }

    private static <E extends RuntimeException> void expectException(Class<E> exceptionClass, Runnable runnable) {
        Exception exception = null;
        try {
            runnable.run();
        } catch (Exception e) {
            exception = e;
        }
        assertNotNull("expected exception " + exceptionClass.getSimpleName(), exception);
        assertSame("expected exception class", exceptionClass, exception.getClass());
    }
}
