package com.synapsenet.core.filesystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FileSystemManagerTest {

    @TempDir
    Path tempDir;

    private FileSystemManager fileSystem;

    @BeforeEach
    void setUp() {
        // FileSystemManager takes workspace path via constructor (no no-arg constructor,
        // no setWorkspace — workspace is final and set at construction time).
        fileSystem = new FileSystemManager(tempDir.toString());
    }

    @Test
    void testWriteAndReadFile() throws Exception {
        String content = "def calculate(a, b):\n    return a + b\n";

        fileSystem.writeFile("calculator.py", content);
        String readContent = fileSystem.readFile("calculator.py");

        assertEquals(content, readContent);
    }

    @Test
    void testReadLines() throws Exception {
        String content = "line1\nline2\nline3\nline4\nline5";
        fileSystem.writeFile("test.txt", content);

        List<String> lines = fileSystem.readLines("test.txt", 2, 4);

        assertEquals(3, lines.size());
        assertEquals("line2", lines.get(0));
        assertEquals("line4", lines.get(2));
    }

    @Test
    void testGrep() throws Exception {
        fileSystem.writeFile("file1.py", "def calculate():\n    pass");
        fileSystem.writeFile("file2.py", "def compute():\n    pass");

        List<FileSystemManager.SearchResult> results =
            fileSystem.grep("def.*calculate", ".");

        assertEquals(1, results.size());
        assertTrue(results.get(0).getFilePath().contains("file1.py"));
    }

    @Test
    void testPathTraversalPrevention() {
        assertThrows(
            FileSystemManager.FileSystemException.class,
            () -> fileSystem.readFile("../../etc/passwd")
        );
    }

    @Test
    void testFileOverwrite() throws Exception {
        fileSystem.writeFile("file.txt", "first");
        fileSystem.writeFile("file.txt", "second");

        String result = fileSystem.readFile("file.txt");

        assertEquals("second", result);
    }

    @Test
    void testFileCreatedInWorkspace() throws Exception {
        fileSystem.writeFile("newfile.txt", "hello");

        Path created = tempDir.resolve("newfile.txt");

        assertTrue(Files.exists(created));
    }

    @Test
    void testFileTree() throws Exception {
        fileSystem.writeFile("file1.py", "test");
        Files.createDirectory(tempDir.resolve("subdir"));
        fileSystem.writeFile("subdir/file2.py", "test");

        String tree = fileSystem.getFileTree(".", 2);

        assertTrue(tree.contains("file1.py"));
        assertTrue(tree.contains("subdir"));
    }

    @Test
    void testListFiles() throws Exception {
        fileSystem.writeFile("file1.txt", "test");
        fileSystem.writeFile("file2.txt", "test");

        List<String> files = fileSystem.listFiles(".");

        assertTrue(files.size() >= 2);
        assertTrue(files.contains("file1.txt"));
        assertTrue(files.contains("file2.txt"));
    }

    @Test
    void testFileExists() throws Exception {
        fileSystem.writeFile("exists.txt", "test");

        assertTrue(fileSystem.fileExists("exists.txt"));
        assertFalse(fileSystem.fileExists("notexists.txt"));
    }

    @Test
    void testGetWorkspacePath() {
        String path = fileSystem.getWorkspacePath();
        assertNotNull(path);
        assertFalse(path.isBlank());
        // Should resolve to the tempDir
        assertTrue(Path.of(path).isAbsolute());
    }
}