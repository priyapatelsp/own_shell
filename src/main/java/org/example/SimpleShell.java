package org.example;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.IOException;
import java.io.File;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;

public class SimpleShell {

    private static File currentDirectory = new File(System.getProperty("user.dir"));
    private static final Map<String, String> environmentVariables = new HashMap<>();

    public static void main(String[] args) {

        SignalHandler signalHandler = new SignalHandler();
        signalHandler.setupSignalHandler();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("\nShell is exiting :)");
            }));

            String command;
            while (true) {
                System.out.print("ccsh> ");
                command = reader.readLine();
                if (command == null || command.trim().isEmpty()) {
                    continue;
                }
                command = command.trim();

                if (command.contains("|")) {
                    handlePipes(command);
                } else {
                    String[] commandParts = command.split("\\s+");
                    if (commandParts.length == 0) {
                        continue;
                    }

                    String cmd = commandParts[0];

                    if (cmd.equalsIgnoreCase("exit")) {
                        System.out.println("Exiting shell.");
                        break;
                    } else if (cmd.equalsIgnoreCase("pwd")) {
                        System.out.println(currentDirectory.getAbsolutePath());
                    } else if (cmd.equalsIgnoreCase("cd")) {
                        if (commandParts.length < 2) {
                            System.err.println("cd: missing argument");
                        } else {
                            String path = commandParts[1];
                            File newDir = new File(currentDirectory, path);
                            if (newDir.isDirectory()) {
                                currentDirectory = newDir;
                            } else {
                                System.err.println("cd: no such file or directory: " + path);
                            }
                        }
                    } else if (cmd.equalsIgnoreCase("unset")) {
                        if (commandParts.length < 2) {
                            System.err.println("unset: missing argument");
                        } else {
                            String varName = commandParts[1];
                            if (environmentVariables.remove(varName) != null) {
                                System.out.println("Unset " + varName);
                            } else {
                                System.err.println("unset: variable not set: " + varName);
                            }
                        }
                    } else if (cmd.equalsIgnoreCase("export")) {
                        if (commandParts.length < 2) {
                            System.err.println("export: missing argument");
                        } else {
                            String[] keyValue = commandParts[1].split("=", 2);
                            if (keyValue.length != 2) {
                                System.err.println("export: invalid format. Use KEY=value");
                            } else {
                                String key = keyValue[0];
                                String value = keyValue[1];
                                environmentVariables.put(key, value);
                                System.out.println("Exported " + key + "=" + value);
                            }
                        }
                    } else {
                        executeCommand(commandParts);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Error reading input: " + e.getMessage());
        }
    }

    private static void handlePipes(String command) {
        String[] commands = command.split("\\|");
        List<Process> processes = new ArrayList<>();
        Process previousProcess = null;

        try {
            for (int i = 0; i < commands.length; i++) {
                String[] commandParts = commands[i].trim().split("\\s+");
                ProcessBuilder builder = new ProcessBuilder(commandParts);
                builder.directory(currentDirectory);
                builder.environment().putAll(environmentVariables);
                builder.redirectErrorStream(true);

                if (i > 0) builder.redirectInput(ProcessBuilder.Redirect.PIPE);

                Process process = builder.start();
                if (previousProcess != null) {
                    try (InputStream previousProcessOutput = previousProcess.getInputStream();
                         OutputStream currentProcessInput = process.getOutputStream()) {
                        copyStream(previousProcessOutput, currentProcessInput);
                    }
                }
                processes.add(process);
                previousProcess = process;
            }


            if (previousProcess != null) {
                try (BufferedReader processOutput = new BufferedReader(new InputStreamReader(previousProcess.getInputStream()))) {
                    String line;
                    while ((line = processOutput.readLine()) != null) {
                        System.out.println(line);
                    }
                }


                try {
                    previousProcess.waitFor();
                } catch (InterruptedException e) {
                    System.err.println("Process interrupted.");
                }
            }
        } catch (IOException e) {
            System.err.println("Error executing piped command: " + e.getMessage());
        } finally {
            for (Process process : processes) {
                process.destroy();
            }
        }
    }

    private static void executeCommand(String[] commandParts) {
        try {
            ProcessBuilder processBuilder = new ProcessBuilder(commandParts);
            processBuilder.directory(currentDirectory);
            processBuilder.environment().putAll(environmentVariables);
            processBuilder.redirectErrorStream(true);
            Process process = processBuilder.start();

            try (BufferedReader processOutput = new BufferedReader(new InputStreamReader(process.getInputStream()));
                 BufferedReader errorOutput = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {

                String line;
                while ((line = processOutput.readLine()) != null) {
                    System.out.println(line);
                }
                String errorLine;
                while ((errorLine = errorOutput.readLine()) != null) {
                    System.err.println("Error: " + errorLine);
                }
            }
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                System.err.println("Process interrupted.");
                process.destroy();
            } finally {
                process.destroy();
            }
        } catch (IOException e) {
            System.err.println("Error executing command: " + e.getMessage());
        }
    }

    private static void copyStream(InputStream input, OutputStream output) throws IOException {
        byte[] buffer = new byte[1024];
        int bytesRead;
        while ((bytesRead = input.read(buffer)) != -1) {
            output.write(buffer, 0, bytesRead);
        }
        output.flush();
    }

    private static class SignalHandler {
        public void setupSignalHandler() {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.out.println("Received interrupt signal (SIGINT) - shell will not exit.");
            }));
        }
    }
}
