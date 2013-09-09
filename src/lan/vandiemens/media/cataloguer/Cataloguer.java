package lan.vandiemens.media.cataloguer;

import java.io.*;
import lan.vandiemens.media.analysis.MediaInfoHelper;
import static lan.vandiemens.media.analysis.MediaInfoHelper.getMediaInfoCliExecutable;
import lan.vandiemens.media.manager.VersionInfo;
import lan.vandiemens.util.file.FileExtensionFilter;
import lan.vandiemens.util.file.FileUtils;
import lan.vandiemens.util.file.Md5FileGenerator;


/**
 * @author Victor
 */
public class Cataloguer {

    /**
     * MediaInfo's supported container file formats.
     */
    public static final String[] supportedFormats = {"mkv", "avi", "divx", "mp4", "ogm", "wmv"};
    public static final String MEDIAINFO_FILE_EXTENSION = "mnfo";
    public static final String MD5_FILE_EXTENSION = "md5";
    /**
     * The directory containing the movie files to be scanned.
     */
    private File moviesDirectory = null;
    /**
     * A flag indicating that semicolons in movie filenames will be translated
     * to colons in media info file.
     * NOTE: In general, movie titles does not have semicolons but colons.
     * Nevertheless, Windows OS file system has limitations regarding characters
     * that can be used in file names, the colon being one of them.
     */
    private boolean isSemicolonToColonEnabled = true;
    /**
     * A flag indicating that no MD5 hash will be calculated.
     */
    private boolean mediaInfoOnlyEnabled = false;
    /**
     * A flag indicating that the media filename will be renamed taking the info
     * from the media info file, if it exists.
     * NOTE: The media info file and the media hash file will be deleted after
     * the media file has been renamed.
     */
    private boolean reverseModeEnabled = false;

    public Cataloguer(File dir) {
        if (dir == null) {
            throw new NullPointerException();
        }
        if (!dir.exists()) {
            throw new IllegalArgumentException(dir + " doesn't exist!");
        }
        if (!dir.isDirectory()) {
            throw new IllegalArgumentException(dir + " is not a directory!");
        }

        moviesDirectory = dir;
    }

    public boolean isMediaInfoOnlyEnabled() {
        return mediaInfoOnlyEnabled;
    }

    public void setMediaInfoOnlyEnabled(boolean enabled) {
        mediaInfoOnlyEnabled = enabled;
    }

    public boolean isReverseModeEnabled() {
        return reverseModeEnabled;
    }

    public void setReverseModeEnabled(boolean enabled) {
        reverseModeEnabled = enabled;
    }

    /**
     * Begin to catalog all media containers located in the media directory.
     */
    public void start() {

        // Check if MediaInfo is installed on this system
        if (getMediaInfoCliExecutable().exists()) {
            System.out.println("MediaInfo found\n");
        } else {
            System.err.println("MediaInfo not found!");
            System.exit(0);
        }

        // Get list of media files in the given movie directory
        System.out.println("Media directory listing:");
        File[] mediaFiles = moviesDirectory.listFiles(new FileExtensionFilter(supportedFormats));
        for (File mediaFile : mediaFiles) {
            System.out.println("Media: " + mediaFile.getName());
        }
        System.out.println();

        // Get list of media information files in the given movie directory
        File[] mediaInfoFiles = moviesDirectory.listFiles(new FileExtensionFilter(new String[]{ MEDIAINFO_FILE_EXTENSION }));
        for (File mediaInfoFile : mediaInfoFiles) {
            System.out.println("Media Info: " + mediaInfoFile.getName());
        }
        System.out.println();

        for (File mediaFile : mediaFiles) {
            // Process only the movies that hasn't been catalogued previously
            if (!reverseModeEnabled && !isCataloged(mediaFile, mediaInfoFiles)) {
                if (process(mediaFile)) {
                    System.out.println(mediaFile.getName() + " processed successfully");
                } else {
                    System.out.println(mediaFile.getName() + " processed with errors");
                }
            } else {
                if (reverseModeEnabled) {
                    reverse(mediaFile);
                } else {
                    System.out.println("Skipped > " + mediaFile.getName());
                }
            }
        }
    }

    /**
     * Generates media info, calculates MD5 hash and renames the container.
     * <p>
     * NOTE: The media info is generated by MediaInfo and saved to a file with
     * the same name as the container and the extension .info.txt. Also, the MD5
     * hash of both files (container and media info) is saved to a .md5 file.
     * In addition, the web source, the uploader name, and the original title
     * are stripped off the container name (if they exist).
     *
     * @param containerFile the media file to be processed
     * @return <code>true</code> if the media file was successfully processed,
     *         <code>false</code> otherwise
     */
    private boolean process(File containerFile) {
        // Check if the media file exists
        System.out.println("Processing " + containerFile.getName() + "...");

        // Parse media container filename
        String container = containerFile.getName();

        boolean isTvSeries = false;

        String movieName;
        String fileExtension = null;
        String originalTitle = null;
        String sourceWeb = null;
        String uploader = null;
        String ripper = null;
        String sourceType = null;

        // Strip off the file extension
        int index = container.lastIndexOf(".");
        if (index > -1) {
            fileExtension = container.substring(index + 1);
            container = container.substring(0, index);
        }

        String[] tokens = container.split("_");
        switch (tokens.length) {
            case 6:
                ripper = tokens[5];
            case 5:
                sourceType = tokens[4];
            case 4:
                originalTitle = tokens[3].replaceAll("--", "_");
                if (originalTitle.equals("=")) {
                    originalTitle = tokens[0];
                }
                if (isSemicolonToColonEnabled) {
                    originalTitle = originalTitle.replace(';', ':');
                }
            case 3:
                uploader = tokens[2].replaceAll("--", "_");
            case 2:
                sourceWeb = tokens[1].replaceAll("--", "_");
                switch (sourceWeb) {
                    case "v":
                        sourceWeb = "www.vagos.es";
                        break;
                    case "tpb":
                        sourceWeb = "thepiratebay.se";
                        break;
                    case "phd":
                        sourceWeb = "publichd.eu";
                        break;
                }
            case 1:
                movieName = tokens[0];
                if (movieName.lastIndexOf(" - [") >= 0) {
                    isTvSeries = true;
                }
                break;
            default:
                System.out.println("Invalid container name");
                return false;
        }

        // Save media container information
        String[] commandArray = new String[]{ MediaInfoHelper.getMediaInfoCliExecutable().getAbsolutePath(),
                                              containerFile.getAbsolutePath()};

        Process process; // The process to be spawned
        BufferedReader reader;
        StringBuilder builder;

        try {
            process = Runtime.getRuntime().exec(commandArray);
            reader = new BufferedReader(new InputStreamReader(process.getInputStream()));

            // Add Release information
            builder = new StringBuilder(4096); // Typical media info file size
            builder.append("Release\r\n");
            builder.append("Source Web                               : ");
            builder.append(sourceWeb == null ? "Unknown" : sourceWeb);
            builder.append("\r\nSource Type                              : ");
            builder.append(sourceType == null ? "Unknown" : sourceType);
            builder.append("\r\nRipper                                   : ");
            builder.append(ripper == null ? "Unknown" : ripper);
            builder.append("\r\nUploader                                 : ");
            builder.append(uploader == null ? "Unknown" : uploader);
            builder.append("\r\n\r\n");

            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("Unique ID")) {
                    continue;
                } else if (line.startsWith("Complete name")) {
                    if (isTvSeries) {
                        builder.append("Complete name                            : /TV Series/");
                    } else {
                        builder.append("Complete name                            : /Movies/");
                    }
                    builder.append(movieName);
                    builder.append(fileExtension == null ? "" : ("." + fileExtension));
                    builder.append("\r\n");
                    builder.append("Original title                           : ");
                    builder.append(originalTitle == null ? movieName.replace(';', ':') : originalTitle);
                    builder.append("\r\n");
                } else {
                    builder.append(line);
                    builder.append("\r\n");
                }
            }
            int length = builder.length();
            builder.delete(length - 2, length); // Deletes the last (and unnecessary) CRLF sequence
            reader.close();

            // Save the generated media info
            String mediaInfo = builder.toString();
            System.out.println("Media Info description:\n" + mediaInfo);
            File mediaInfoFile = new File(containerFile.getParent() + File.separator + movieName + "." + MEDIAINFO_FILE_EXTENSION);
            try (PrintWriter writer = new PrintWriter(new FileWriter(mediaInfoFile))) {
                writer.print(mediaInfo);
            }

            // Rename media container file
            movieName += (fileExtension == null ? "" : ("." + fileExtension));
            File desiredFile = new File(containerFile.getParent() + File.separator + movieName);
            if (containerFile.renameTo(desiredFile)) {
                System.out.println("\"" + containerFile.getName() + "\" has been renamed to \"" + movieName + "\"");
            } else {
                System.out.println("\"" + containerFile.getName() + "\" could not be renamed to \"" + movieName + "\"");
                System.exit(0);
            }

            // Calculate the MD5 digest
            if (!mediaInfoOnlyEnabled) {
                saveMediaHashFile(desiredFile, mediaInfoFile);
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            return false;
        }

        return true;
    }

    /**
     * Rebuilds original media file name from media info.
     * <p>
     * NOTE: The media info was generated by MediaInfo and saved to a file with
     * the same name as the container and the extension .info.txt, which is
     * deleted now. Likewise, the MD5 hash file is deleted too.
     *
     * @param mkvFile the media file to be processed
     * @return <code>true</code> if the media file was successfully reversed,
     *         <code>false</code> otherwise
     */
    private boolean reverse(File mkvFile) {
        // Get info from media info file
        String containerName = FileUtils.getNameWithoutExtension(mkvFile);
        File mediaInfoFile = new File(mkvFile.getParent(), containerName + ".info.txt");
        BufferedReader reader;

        try {
            reader = new BufferedReader(new FileReader(mediaInfoFile));
        } catch (FileNotFoundException ex) {
            System.out.println("Media Info file not found: " + mediaInfoFile.getAbsolutePath());
            return false;
        }

        int i = 0;
        String[] lines = new String[9]; // Significant lines in media info file
        try {
            while (i < lines.length && (lines[i] = reader.readLine()) != null) {
                i++;
            }
            reader.close();

            if (i != lines.length) {
                System.err.println("Invalid media info file: " + mediaInfoFile.getAbsolutePath());
                return false;
            }
        } catch (IOException ex) {
            System.err.println("Error when reading media info file: " + mediaInfoFile.getAbsolutePath());
            return false;
        }

        // Rebuild media file name from media info data
        String sourceWeb;
        int colonIndex;
        if (lines[1].startsWith("Source Web")) {
            colonIndex = lines[1].indexOf(":");
            if (colonIndex > 0) {
                sourceWeb = lines[1].substring(colonIndex + 2);
            } else {
                System.err.println("Invalid source web line in " + mediaInfoFile.getAbsolutePath());
                return false;
            }
        } else {
            System.err.println("Invalid source web line in " + mediaInfoFile.getAbsolutePath());
            return false;
        }

        String sourceType;
        if (lines[2].startsWith("Source Type")) {
            colonIndex = lines[2].indexOf(":");
            if (colonIndex > 0) {
                sourceType = lines[2].substring(colonIndex + 2);
            } else {
                System.err.println("Invalid source type line in " + mediaInfoFile.getAbsolutePath());
                return false;
            }
        } else {
            System.err.println("Invalid source type line in " + mediaInfoFile.getAbsolutePath());
            return false;
        }

        String ripper;
        if (lines[3].startsWith("Ripper")) {
            colonIndex = lines[3].indexOf(":");
            if (colonIndex > 0) {
                ripper = lines[3].substring(colonIndex + 2);
            } else {
                System.err.println("Invalid ripper line in " + mediaInfoFile.getAbsolutePath());
                return false;
            }
        } else {
            System.err.println("Invalid ripper line in " + mediaInfoFile.getAbsolutePath());
            return false;
        }

        String uploader;
        if (lines[4].startsWith("Uploader")) {
            colonIndex = lines[4].indexOf(":");
            if (colonIndex > 0) {
                uploader = lines[4].substring(colonIndex + 2);
            } else {
                System.err.println("Invalid uplouder line in " + mediaInfoFile.getAbsolutePath());
                return false;
            }
        } else {
            System.err.println("Invalid uploader line in " + mediaInfoFile.getAbsolutePath());
            return false;
        }

        String originalTitle;
        if (lines[8].startsWith("Original title")) {
            colonIndex = lines[8].indexOf(":");
            if (colonIndex > 0) {
                originalTitle = lines[8].substring(colonIndex + 2).replace(':', ';');
            } else {
                System.err.println("Invalid original title line in " + mediaInfoFile.getAbsolutePath());
                return false;
            }
        } else {
            System.err.println("Invalid original title line in " + mediaInfoFile.getAbsolutePath());
            return false;
        }

        System.out.println("Rebuild name: " + containerName + "_" + sourceWeb + "_" + uploader + "_" + originalTitle + "_" + sourceType + "_" + ripper);
        StringBuilder builder = new StringBuilder(containerName).append("_").append(sourceWeb).append("_").append(uploader).append("_").append(originalTitle).append("_").append(sourceType).append("_").append(ripper).append(".").append(FileUtils.getExtension(mkvFile));
        mkvFile.renameTo(new File(mkvFile.getParent(), new String(builder)));

        // Delete MD5 hash file
        File md5HashFile = new File(mediaInfoFile.getParent(), containerName + "." + MD5_FILE_EXTENSION);
        if (md5HashFile.exists()) {
            if (md5HashFile.delete()) {
                System.out.println(md5HashFile.getAbsolutePath() + " successfully deleted");
            } else {
                System.err.println(md5HashFile.getAbsolutePath() + " could not be deleted");
                return false;
            }
        } else {
            System.out.println("MD5 hash file not found: " + md5HashFile.getAbsolutePath());
        }

        // Delete media info file
        if (mediaInfoFile.delete()) {
            System.out.println(mediaInfoFile.getAbsolutePath() + " successfully deleted");
        } else {
            System.err.println(mediaInfoFile.getAbsolutePath() + " could not be deleted");
            return false;
        }

        return true;
    }

    /**
     * Checks if a media file is already cataloged.
     *
     * @param mediaFile the file to be checked
     * @param infoFiles the media info files corresponding to media files which
     *                  are already cataloged
     * @return <code>true</code> if the given media file is already cataloged,
     *         <code>false</code> otherwise
     */
    private boolean isCataloged(File mediaFile, File[] infoFiles) {
        String mediaName = FileUtils.getNameWithoutExtension(mediaFile);
        String infoName;
        for (File infoFile : infoFiles) {
            infoName = FileUtils.getNameWithoutExtension(infoFile);
            if (infoName.equals(mediaName)) {
                return true;
            }
        }

        return false;
    }

    private void saveMediaHashFile(File mediaFile, File infoFile) throws IOException {
        Md5FileGenerator generator = new Md5FileGenerator(mediaFile, infoFile);
        generator.setApplicationName(VersionInfo.getApplicationFullName());
        File outputFile = new File(mediaFile.getParentFile(), FileUtils.getNameWithoutExtension(mediaFile) + "." + MD5_FILE_EXTENSION);
        generator.writeTo(outputFile);
    }

    static void showUsage() {
        System.out.println("Usage:");
        System.out.println("    Cataloguer [-i | -r] <movies_dir>");
        System.out.println("");
        System.out.println("Options:");
        System.out.println("    -i, --info     Generates only the media info file, without MD5 hash (Faster).");
        System.out.println("    -r, --reverse  Rebuilds the original media file name from the media info file.");
        System.out.println("                   Also, deletes the media info file and the MD5 hash file, if any.");
    }

    /**
     * @param args the command line arguments
     */
    public static void main(String[] args) {
        // Check arguments entered by the user
        if (args.length == 0) {
            showUsage();
            System.exit(0);
        }

        boolean isMediaInfoOnlyEnabled = false;
        boolean isReverseMode = false;

        // Parse argument list
        for (int i = 0; i < args.length - 1; i++) {
            switch (args[i]) {
                case "-i":
                case "--info":
                    isMediaInfoOnlyEnabled = true;
                    break;
                case "-r":
                case "--reverse":
                    isReverseMode = true;
                    break;
                default:
                    showUsage();
                    System.exit(0);
                    break;
            }
        }
        if (isReverseMode && isMediaInfoOnlyEnabled) {
            System.out.println("Media info only and reverse mode can't be used together.");
            showUsage();
            System.exit(0);
        }

        // Parse the media directory
        File moviesDir = new File(args[args.length - 1]);
        Cataloguer cataloguer = new Cataloguer(moviesDir);
        if (isReverseMode) {
            cataloguer.setReverseModeEnabled(true);
        } else if (isMediaInfoOnlyEnabled) {
            cataloguer.setMediaInfoOnlyEnabled(true);
        }
        cataloguer.start();
    }
}
