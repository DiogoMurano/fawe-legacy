/*
 *    Copyright 2022 Whilein
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */

package w.agent;

import com.sun.tools.attach.VirtualMachine;

import java.lang.instrument.Instrumentation;
import java.net.URL;

/**
 * @author whilein
 */
final class AgentMain {

    @SuppressWarnings("unused")
    private static Instrumentation instrumentation;

    public void main(final String[] args) throws Exception {
        final VirtualMachine vm = VirtualMachine.attach(args[0]);

        final URL jar = AgentMain.class.getProtectionDomain().getCodeSource()
                .getLocation();

        vm.loadAgent(jar.getFile());
    }

    public void agentmain(final String args, final Instrumentation instrumentation) {
        AgentMain.instrumentation = instrumentation;
    }

}
