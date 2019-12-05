package canvas.autograder;

import java.nio.file.Path;
import java.util.Set;

public final class Lab {
    public final int canvasAssnId;
    public final String shortName;
    public final Path fileToSubmit;
    public final Set<String> archiveContents;
    public final Path repoLeafDir;

    /**
     *
     * @param caid Canvas Assignment ID
     * @param name short name for this lab
     * @param file name of file that students should submit
     * @param archive file names we expect inside .zip archive (null if this lab doesn't expect a .zip upload)
     * @param leafDir this lab's leaf directory within the git repo
     */
    Lab(int caid, String name, Path file, Set<String> archive, Path leafDir) {
        canvasAssnId = caid;
        shortName = name;
        fileToSubmit = file;
        archiveContents = archive;
        repoLeafDir = leafDir;
    }
}
