package com.leadrdrk.umapatcher.documentsprovider;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.DocumentsProvider;
import android.system.ErrnoException;
import android.system.Os;
import android.system.StructStat;
import android.webkit.MimeTypeMap;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;

/**
 * This class is a modified version of InternalDataDocumentsProvider.java
 * from the ReVanced project.
 *
 * @link https://gitlab.com/ReVanced/revanced-patches/-/blob/main/extensions/all/misc/directory/documentsprovider/export-internal-data-documents-provider/src/main/java/app/revanced/extension/all/misc/directory/documentsprovider/InternalDataDocumentsProvider.java
 */
public final class InternalDataDocumentsProvider extends DocumentsProvider {

    private static final String[] DEFAULT_ROOT_PROJECTION = {
        DocumentsContract.Root.COLUMN_ROOT_ID,
        DocumentsContract.Root.COLUMN_MIME_TYPES,
        DocumentsContract.Root.COLUMN_FLAGS,
        DocumentsContract.Root.COLUMN_ICON,
        DocumentsContract.Root.COLUMN_TITLE,
        DocumentsContract.Root.COLUMN_SUMMARY,
        DocumentsContract.Root.COLUMN_DOCUMENT_ID
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = {
        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
        DocumentsContract.Document.COLUMN_MIME_TYPE,
        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
        DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        DocumentsContract.Document.COLUMN_FLAGS,
        DocumentsContract.Document.COLUMN_SIZE
    };

    @SuppressWarnings("OctalInteger")
    private static final int S_IFMT = 0170000;
    @SuppressWarnings("OctalInteger")
    private static final int S_IFLNK = 0120000;

    private String packageName;
    private File dataDirectory;

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public void attachInfo(Context context, ProviderInfo providerInfo) {
        super.attachInfo(context, providerInfo);
        this.packageName = context.getPackageName();
        this.dataDirectory = context.getFilesDir().getParentFile();
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        if (projection == null) {
            projection = DEFAULT_ROOT_PROJECTION;
        }

        final Context context = getContext();
        final ApplicationInfo info = context.getApplicationInfo();

        final MatrixCursor cursor = new MatrixCursor(projection);
        final MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Root.COLUMN_ROOT_ID, this.packageName);
        row.add(DocumentsContract.Root.COLUMN_DOCUMENT_ID, this.packageName);
        row.add(DocumentsContract.Root.COLUMN_SUMMARY, this.packageName);
        row.add(DocumentsContract.Root.COLUMN_FLAGS,
            DocumentsContract.Root.FLAG_LOCAL_ONLY
            | DocumentsContract.Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(DocumentsContract.Root.COLUMN_TITLE, info.loadLabel(context.getPackageManager()));
        row.add(DocumentsContract.Root.COLUMN_MIME_TYPES, "*/*");
        row.add(DocumentsContract.Root.COLUMN_ICON, info.icon);
        return cursor;
    }

    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        if (projection == null) {
            projection = DEFAULT_DOCUMENT_PROJECTION;
        }

        final MatrixCursor cursor = new MatrixCursor(projection);
        addRowForDocument(cursor, documentId, resolveDocumentId(documentId));
        return cursor;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        if (parentDocumentId.endsWith("/")) {
            parentDocumentId = parentDocumentId.substring(0, parentDocumentId.length() - 1);
        }
        if (projection == null) {
            projection = DEFAULT_DOCUMENT_PROJECTION;
        }

        final MatrixCursor cursor = new MatrixCursor(projection);
        final File parent = resolveDocumentId(parentDocumentId);
        if (!parent.isDirectory()) {
            throw new FileNotFoundException("Not a directory: " + parentDocumentId);
        }

        final File[] children = parent.listFiles();
        if (children != null) {
            for (File child : children) {
                addRowForDocument(cursor, parentDocumentId + "/" + child.getName(), child);
            }
        }
        return cursor;
    }

    @Override
    public ParcelFileDescriptor openDocument(String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = resolveDocumentId(documentId);
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.parseMode(mode));
    }

    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        final File directory = resolveDocumentId(parentDocumentId);

        File file = new File(directory, displayName);
        int counter = 2;
        while (file.exists()) {
            file = new File(directory, displayName + " (" + counter + ")");
            counter++;
        }

        final boolean created;
        try {
            created = DocumentsContract.Document.MIME_TYPE_DIR.equals(mimeType) ? file.mkdir() : file.createNewFile();
        } catch (IOException e) {
            throw new FileNotFoundException("Failed to create document in " + parentDocumentId + " named " + displayName);
        }
        if (!created) {
            throw new FileNotFoundException("Failed to create document in " + parentDocumentId + " named " + displayName);
        }

        final String parent = parentDocumentId.endsWith("/") ? parentDocumentId : parentDocumentId + "/";
        return parent + file.getName();
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        if (isRootDocumentId(documentId)) {
            throw new FileNotFoundException("Cannot delete the root document");
        }
        final File file = resolveDocumentId(documentId);
        if (!deleteRecursively(file)) {
            throw new FileNotFoundException("Failed to delete document " + documentId);
        }
    }

    @Override
    public void removeDocument(String documentId, String parentDocumentId) throws FileNotFoundException {
        deleteDocument(documentId);
    }

    @Override
    public String renameDocument(String documentId, String displayName) throws FileNotFoundException {
        if (isRootDocumentId(documentId)) {
            throw new FileNotFoundException("Cannot rename the root document");
        }
        final File file = resolveDocumentId(documentId);
        final File renamed = new File(file.getParentFile(), displayName);
        if (!file.renameTo(renamed)) {
            throw new FileNotFoundException(
                    "Failed to rename document " + documentId + " to " + displayName);
        }

        final int lastSeparator = documentId.lastIndexOf('/');
        if (lastSeparator < 0) {
            throw new FileNotFoundException("Cannot rename the root document");
        }
        return documentId.substring(0, lastSeparator) + "/" + displayName;
    }

    @Override
    public String moveDocument(String sourceDocumentId, String sourceParentDocumentId, String targetParentDocumentId) throws FileNotFoundException {
        if (isRootDocumentId(sourceDocumentId)) {
            throw new FileNotFoundException("Cannot move the root document");
        }
        final File source = resolveDocumentId(sourceDocumentId);
        final File targetDirectory = resolveDocumentId(targetParentDocumentId);
        final File target = new File(targetDirectory, source.getName());
        if (target.exists() || !source.renameTo(target)) {
            throw new FileNotFoundException("Failed to move document " + sourceDocumentId + " to " + targetParentDocumentId);
        }

        final String parent = targetParentDocumentId.endsWith("/")
                ? targetParentDocumentId
                : targetParentDocumentId + "/";
        return parent + target.getName();
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId + "/")
                || documentId.equals(parentDocumentId);
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        return resolveMimeType(resolveDocumentId(documentId));
    }

    private File resolveDocumentId(String documentId) throws FileNotFoundException {
        if (this.packageName == null || this.dataDirectory == null || documentId == null) {
            throw new FileNotFoundException("Invalid document ID: " + documentId);
        }
        return resolveDocumentId(this.packageName, this.dataDirectory, documentId);
    }

    private boolean isRootDocumentId(String documentId) {
        return documentId != null && this.packageName != null
            && (documentId.equals(this.packageName)
            || documentId.equals(this.packageName + "/"));
    }

    static File resolveDocumentId(String packageName, File dataDirectory, String documentId) throws FileNotFoundException {
        if (packageName == null || dataDirectory == null || documentId == null) {
            throw new FileNotFoundException("Invalid document ID: " + documentId);
        }

        // Root document ID: the bare package name, optionally with one
        // trailing slash.
        if (documentId.equals(packageName) || documentId.equals(packageName + "/")) {
            if (!dataDirectory.isDirectory()) {
                throw new FileNotFoundException("Data directory unavailable: " + documentId);
            }
            return dataDirectory;
        }

        // Tolerate a single trailing slash on child IDs.
        if (documentId.endsWith("/")) {
            documentId = documentId.substring(0, documentId.length() - 1);
        }

        if (!documentId.startsWith(packageName + "/")) {
            throw new FileNotFoundException("Invalid document ID: " + documentId);
        }

        final String relativePath = documentId.substring(packageName.length() + 1);
        for (String segment : relativePath.split("/", -1)) {
            if (segment.isEmpty() || segment.equals(".") || segment.equals("..")) {
                throw new FileNotFoundException("Invalid document ID: " + documentId);
            }
        }

        final File file = new File(dataDirectory, relativePath);
        if (!file.exists()) {
            throw new FileNotFoundException("Not found: " + documentId);
        }
        return file;
    }

    private static String resolveMimeType(File file) {
        if (file.isDirectory()) {
            return DocumentsContract.Document.MIME_TYPE_DIR;
        }

        final String name = file.getName();
        final int extensionStart = name.lastIndexOf('.');
        if (extensionStart < 0 || extensionStart == name.length() - 1) {
            return "application/octet-stream";
        }

        final String extension = name.substring(extensionStart + 1).toLowerCase();
        final String mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
        return mimeType != null ? mimeType : "application/octet-stream";
    }

    private static boolean deleteRecursively(File root) {
        if (root.isDirectory() && !isSymbolicLink(root)) {
            final File[] children = root.listFiles();
            if (children != null) {
                for (File child : children) {
                    if (!deleteRecursively(child)) {
                        return false;
                    }
                }
            }
        }
        return root.delete();
    }

    private static boolean isSymbolicLink(File file) {
        try {
            final StructStat lstat = Os.lstat(file.getPath());
            return (lstat.st_mode & S_IFMT) == S_IFLNK;
        } catch (ErrnoException e) {
            return false;
        }
    }

    private void addRowForDocument(MatrixCursor cursor, String documentId, File file) {
        int flags = 0;
        if (file.isDirectory()) {
            // Prefer list view for directories.
            flags |= DocumentsContract.Document.FLAG_DIR_PREFERS_LAST_MODIFIED;
        }
        if (file.canWrite()) {
            if (file.isDirectory()) {
                flags |= DocumentsContract.Document.FLAG_DIR_SUPPORTS_CREATE;
            }
            flags |= DocumentsContract.Document.FLAG_SUPPORTS_WRITE
                | DocumentsContract.Document.FLAG_SUPPORTS_DELETE
                | DocumentsContract.Document.FLAG_SUPPORTS_RENAME
                | DocumentsContract.Document.FLAG_SUPPORTS_MOVE;
        }

        final MatrixCursor.RowBuilder row = cursor.newRow();
        row.add(DocumentsContract.Document.COLUMN_DOCUMENT_ID, documentId);
        row.add(DocumentsContract.Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(DocumentsContract.Document.COLUMN_SIZE, file.length());
        row.add(DocumentsContract.Document.COLUMN_MIME_TYPE, resolveMimeType(file));
        row.add(DocumentsContract.Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(DocumentsContract.Document.COLUMN_FLAGS, flags);
    }
}
