package com.rincliu.library.common.persistence.rootmanager.container;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeoutException;

import com.rincliu.library.common.persistence.rootmanager.exception.PermissionException;
import com.rincliu.library.common.persistence.rootmanager.utils.RootUtils;

public class Shell {

    private final static String TAG = Shell.class.getSimpleName();

    private final Process proc;

    private final DataInputStream in;

    private final DataOutputStream out;

    private final List<Command> commands = new ArrayList<Command>();

    private boolean close = false;

    private static int shellTimeout = 10000;

    private static String error = "";

    private static final String token = "F*D^W@#FGF";

    private static Shell rootShell = null;

    private static Shell customShell = null;

    private Shell(String cmd) throws IOException, TimeoutException, PermissionException {

        RootUtils.Log(TAG, "Starting shell: " + cmd);

        proc = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        in = new DataInputStream(proc.getInputStream());
        out = new DataOutputStream(proc.getOutputStream());

        Worker worker = new Worker(proc, in, out);
        worker.start();

        try {
            worker.join(shellTimeout);

            if (worker.exit == -911) {
                proc.destroy();

                throw new TimeoutException(error);
            }
            if (worker.exit == -42) {
                proc.destroy();
                throw new PermissionException("Root Access Denied");
            } else {
                new Thread(input, "Shell Input").start();
                new Thread(output, "Shell Output").start();
            }
        } catch (InterruptedException ex) {
            worker.interrupt();
            Thread.currentThread().interrupt();
            throw new TimeoutException();
        }
    }

    public static Shell getOpenShell() {
        if (rootShell != null) {
            return rootShell;
        } else if (customShell != null) {
            return customShell;
        } else {
            return null;
        }
    }

    public static Shell startRootShell() throws IOException, TimeoutException, PermissionException {
        return Shell.startRootShell(shellTimeout);
    }

    public static Shell startRootShell(int timeout) throws IOException, TimeoutException, PermissionException {
        Shell.shellTimeout = timeout;

        if (rootShell == null) {
            RootUtils.Log("Starting Root Shell!");
            String cmd = "su";

            int retries = 0;
            while (rootShell == null) {
                try {
                    rootShell = new Shell(cmd);
                } catch (IOException e) {
                    if (retries++ >= 5) {
                        RootUtils.Log("Could not start shell");
                        throw e;
                    }
                }
            }
        } else {
            RootUtils.Log("Using Existing Root Shell!");
        }

        return rootShell;
    }

    public static Shell startCustomShell(String shellPath) throws IOException, TimeoutException, PermissionException {
        return Shell.startCustomShell(shellPath, shellTimeout);
    }

    public static Shell startCustomShell(String shellPath, int timeout) throws IOException, TimeoutException,
            PermissionException {
        Shell.shellTimeout = timeout;

        if (customShell == null) {
            RootUtils.Log("Starting Custom Shell!");
            customShell = new Shell(shellPath);
        } else {
            RootUtils.Log("Using Existing Custom Shell!");
        }

        return customShell;
    }

    public static void runRootCommand(Command command) throws IOException, TimeoutException, PermissionException {
        startRootShell().add(command);
    }

    public static void closeCustomShell() throws IOException {
        if (customShell == null) {
            return;
        }
        customShell.close();
    }

    public static void closeRootShell() throws IOException {
        if (rootShell == null) {
            return;
        }
        rootShell.close();
    }

    public static void closeAll() throws IOException {
        closeRootShell();
        closeCustomShell();
    }

    public static boolean isCustomShellOpen() {
        if (customShell == null) {
            return false;
        } else {
            return true;
        }
    }

    public static boolean isRootShellOpen() {
        if (rootShell == null) {
            return false;
        } else {
            return true;
        }
    }

    public static boolean isAnyShellOpen() {
        if (rootShell != null) {
            return true;
        } else if (customShell != null) {
            return true;
        } else {
            return false;
        }
    }

    private Runnable input = new Runnable() {
        public void run() {
            try {
                writeCommands();
            } catch (IOException e) {
                RootUtils.Log(e.getMessage());
            }
        }
    };

    private void writeCommands() throws IOException {
        try {
            int write = 0;
            while (true) {
                DataOutputStream out;
                synchronized (commands) {
                    while (!close && write >= commands.size()) {
                        commands.wait();
                    }
                    out = this.out;
                }
                if (write < commands.size()) {
                    Command next = commands.get(write);
                    next.writeCommand(out);
                    String line = "\necho " + token + " " + write + " $?\n";
                    out.write(line.getBytes());
                    out.flush();
                    write++;
                } else if (close) {
                    out.write("\nexit 0\n".getBytes());
                    out.flush();
                    out.close();
                    RootUtils.Log("Closing shell");
                    return;
                }
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    private Runnable output = new Runnable() {
        public void run() {
            try {
                readOutput();
            } catch (IOException e) {
                e.printStackTrace();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    };

    private void readOutput() throws IOException, InterruptedException {
        Command command = null;
        int read = 0;
        while (true) {
            String line = in.readLine();
            if (line == null) {
                break;
            }
            if (command == null) {
                if (read >= commands.size()) {
                    if (close) {
                        break;
                    }
                    continue;
                }
                command = commands.get(read);
            }

            int pos = line.indexOf(token);
            if (pos > 0) {
                command.onUpdate(command.getID(), line.substring(0, pos));
            }
            if (pos >= 0) {
                line = line.substring(pos);
                String fields[] = line.split(" ");
                if (fields.length >= 2 && fields[1] != null) {
                    int id = 0;
                    try {
                        id = Integer.parseInt(fields[1]);
                    } catch (NumberFormatException e) {}
                    int exitCode = -1;
                    try {
                        exitCode = Integer.parseInt(fields[2]);
                    } catch (NumberFormatException e) {}
                    if (id == read) {
                        command.setExitCode(exitCode);
                        read++;
                        command = null;
                        continue;
                    }
                }
            }
            command.onUpdate(command.getID(), line);
        }
        RootUtils.Log("Read all output");
        proc.waitFor();
        proc.destroy();
        RootUtils.Log("Shell destroyed");

        while (read < commands.size()) {
            if (command == null) {
                command = commands.get(read);
            }
            command.terminate("Unexpected Termination.");
            command = null;
            read++;
        }
    }

    public Command add(Command command) throws IOException {
        if (close)
            throw new IllegalStateException("Unable to add commands to a closed shell");
        synchronized (commands) {
            commands.add(command);
            commands.notifyAll();
        }

        return command;
    }

    public void close() throws IOException {
        if (this == rootShell) {
            rootShell = null;
        }
        if (this == customShell) {
            customShell = null;
        }
        synchronized (commands) {
            this.close = true;
            commands.notifyAll();
        }
    }

    public int countCommands() {
        return commands.size();
    }

    public void waitFor() throws IOException, InterruptedException {
        close();
        if (commands.size() > 0) {
            Command command = commands.get(commands.size() - 1);
            command.waitForFinish();
        }
    }

    protected static class Worker extends Thread {
        public int exit = -911;

        public Process proc;

        public DataInputStream in;

        public DataOutputStream out;

        private Worker(Process proc, DataInputStream in, DataOutputStream out) {
            this.proc = proc;
            this.in = in;
            this.out = out;
        }

        public void run() {

            try {
                out.write("echo Started\n".getBytes());
                out.flush();

                while (true) {
                    String line = in.readLine();
                    if (line == null) {
                        throw new EOFException();
                    }
                    if ("".equals(line)) {
                        continue;
                    }
                    if ("Started".equals(line)) {
                        this.exit = 1;
                        break;
                    }
                    Shell.error = "unkown error occured.";
                }
            } catch (IOException e) {
                exit = -42;
                if (e.getMessage() != null) {
                    Shell.error = e.getMessage();
                } else {
                    Shell.error = "RootAccess denied?.";
                }
            }

        }
    }
}
