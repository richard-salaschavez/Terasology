/*
 * Copyright 2016 MovingBlocks
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.terasology.rendering.nui.editor.layers;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import com.google.gson.stream.JsonReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.terasology.assets.ResourceUrn;
import org.terasology.input.Keyboard;
import org.terasology.input.device.KeyboardDevice;
import org.terasology.rendering.nui.Canvas;
import org.terasology.rendering.nui.CoreScreenLayer;
import org.terasology.rendering.nui.editor.systems.AbstractEditorSystem;
import org.terasology.rendering.nui.events.NUIKeyEvent;
import org.terasology.rendering.nui.layers.mainMenu.ConfirmPopup;
import org.terasology.rendering.nui.widgets.JsonEditorTreeView;
import org.terasology.rendering.nui.widgets.UITextEntry;
import org.terasology.rendering.nui.widgets.treeView.JsonTree;
import org.terasology.rendering.nui.widgets.treeView.JsonTreeConverter;
import org.terasology.rendering.nui.widgets.treeView.JsonTreeValue;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;

/**
 * A base screen for the NUI screen/skin editors.
 */
public abstract class AbstractEditorScreen extends CoreScreenLayer {
    private final Logger logger = LoggerFactory.getLogger(getClass());

    /**
     * The editor system.
     */
    private AbstractEditorSystem editorSystem;
    /**
     * The editor widget.
     */
    private JsonEditorTreeView editor;
    /**
     * Whether unsaved changes in the editor are present.
     */
    private boolean unsavedChangesPresent;
    /**
     * Whether the autosave has been loaded.
     */
    private boolean autosaveLoaded;

    /**
     * Selects the current asset to be edited.
     *
     * @param urn The urn of the asset.
     */
    public abstract void selectAsset(ResourceUrn urn);

    /**
     * Resets the editor's state based on a tree representation of an asset.
     *
     * @param node The node based on which the editor's state is to be reset.
     */
    protected abstract void resetStateInternal(JsonTree node);

    /**
     * Resets the preview widget based on the editor's current state.
     */
    protected abstract void resetPreviewWidget();

    /**
     * Updates the editor after state or configuration changes.
     */
    protected abstract void updateConfig();

    /**
     * Initialises the widgets, screens etc. used to edit a specified node.
     *
     * @param node The node to be edited.
     */
    protected abstract void editNode(JsonTree node);

    /**
     * Adds a widget selected by the user to the specified node.
     *
     * @param node The node to add a new widget to.
     */
    protected abstract void addWidget(JsonTree node);

    /**
     * @return The path to the backup autosave file.
     */
    protected abstract Path getAutosaveFile();

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // If the autosave is loaded in initialise(), the preview widget is updated before interface component
        // sizes are set, which breaks the editor's layout.
        // Therefore, the autosave is loaded after the first onDraw() call.
        if (!autosaveLoaded) {
            loadAutosave();
            autosaveLoaded = true;
        }
    }

    @Override
    public boolean onKeyEvent(NUIKeyEvent event) {
        if (event.isDown()) {
            int id = event.getKey().getId();
            KeyboardDevice keyboard = event.getKeyboard();
            boolean ctrlDown = keyboard.isKeyDown(Keyboard.KeyId.RIGHT_CTRL) || keyboard.isKeyDown(Keyboard.KeyId.LEFT_CTRL);

            if (id == Keyboard.KeyId.ESCAPE) {
                editorSystem.toggleEditor();
                return true;
            } else if (ctrlDown && id == Keyboard.KeyId.Z) {
                undo();
                return true;
            } else if (ctrlDown && id == Keyboard.KeyId.Y) {
                redo();
                return true;
            } else {
                return false;
            }
        }

        return false;
    }

    @Override
    public boolean isEscapeToCloseAllowed() {
        // Escape to close is handled in onKeyEvent() to pass the editor's state to NUIEditorSystem.
        return false;
    }

    /**
     * Sets the editor's state based on the previous item in the editor widget's history.
     *
     * @see JsonEditorTreeView#undo()
     */
    protected void undo() {
        if (editor.undo()) {
            resetPreviewWidget();
            updateConfig();
        }
    }

    /**
     * Sets the editor's state based on the next item in the editor widget's history.
     *
     * @see JsonEditorTreeView#redo()
     */
    protected void redo() {
        if (editor.redo()) {
            resetPreviewWidget();
            updateConfig();
        }
    }

    /**
     * Resets the editor's state based on a specified {@link JsonTree}.
     *
     * @param node The {@link JsonTree} to reset the state from.
     */
    protected void resetState(JsonTree node) {
        if (unsavedChangesPresent) {
            ConfirmPopup confirmPopup = getManager().pushScreen(ConfirmPopup.ASSET_URI, ConfirmPopup.class);
            confirmPopup.setMessage("Unsaved changes!", "It looks like you've been editing something!" +
                "\r\nAll unsaved changes will be lost. Continue anyway?");
            confirmPopup.setOkHandler(() -> {
                setUnsavedChangesPresent(false);
                deleteAutosave();
                resetStateInternal(node);
            });
        } else {
            resetStateInternal(node);
        }
    }

    /**
     * Saves the contents of the editor as a JSON string to a specified file.
     *
     * @param file The file to save to.
     */
    protected void saveToFile(File file) {
        try (BufferedOutputStream outputStream = new BufferedOutputStream(new FileOutputStream(file))) {
            saveToFile(outputStream);
            setUnsavedChangesPresent(false);
        } catch (IOException e) {
            logger.warn("Could not save asset", e);
        }
    }

    /**
     * Saves the contents of the editor as a JSON string to a specified file.
     *
     * @param path The path to save to.
     */
    protected void saveToFile(Path path) {
        try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(path))) {
            saveToFile(outputStream);
            setUnsavedChangesPresent(false);
        } catch (IOException e) {
            logger.warn("Could not save asset", e);
        }
    }

    /**
     * Updates the autosave file with the current state of the tree.
     */
    protected void updateAutosave() {
        try (BufferedOutputStream outputStream = new BufferedOutputStream(Files.newOutputStream(getAutosaveFile()))) {
            saveToFile(outputStream);
        } catch (IOException e) {
            logger.warn("Could not save to autosave file", e);
        }
    }

    /**
     * Resets the editor based on the state of the autosave file.
     */
    protected void loadAutosave() {
        try (JsonReader reader = new JsonReader(new InputStreamReader(Files.newInputStream(getAutosaveFile())))) {
            reader.setLenient(true);
            String content = new JsonParser().parse(reader).toString();
            JsonTree node = JsonTreeConverter.serialize(new JsonParser().parse(content));
            resetState(node);
            setUnsavedChangesPresent(true);
        } catch (NoSuchFileException ignored) {
        } catch (IOException e) {
            logger.warn("Could not load autosaved info", e);
        }
    }

    /**
     * Deletes the autosave file.
     */
    protected void deleteAutosave() {
        try {
            Files.delete(getAutosaveFile());
        } catch (NoSuchFileException ignored) {
        } catch (IOException e) {
            logger.warn("Could not delete autosave file", e);
        }
    }

    private void saveToFile(BufferedOutputStream outputStream) throws IOException {
        // Serialize tree contents and save to selected file.
        JsonElement json = JsonTreeConverter.deserialize(getEditor().getModel().getNode(0).getRoot());
        String jsonString = new GsonBuilder().setPrettyPrinting().create().toJson(json);
        outputStream.write(jsonString.getBytes());
    }

    /**
     * De-serializes the current state of the editor and copies it to the system clipboard.
     */
    protected void copyJson() {
        if (getEditor().getModel() != null) {
            // Deserialize the state of the editor to a JSON string.
            JsonElement json = JsonTreeConverter.deserialize(getEditor().getModel().getNode(0).getRoot());
            String jsonString = new GsonBuilder().setPrettyPrinting().create().toJson(json);

            // Set the contents of the system clipboard to it.
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            try {
                clipboard.setContents(new StringSelection(jsonString), null);
            } catch (IllegalStateException e) {
                logger.warn("Clipboard inaccessible.", e);
            }
        }
    }

    /**
     * Attempts to serialize the system clipboard's contents - if successful,
     * sets the current state of the editor to the serialized {@link JsonTree}.
     */
    protected void pasteJson() {
        // Get the clipboard contents.
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable t = clipboard.getContents(null);

        // Attempt to convert them to a string.
        String clipboardContents = null;
        try {
            if (t != null) {
                clipboardContents = (String) t.getTransferData(DataFlavor.stringFlavor);
            }
        } catch (UnsupportedFlavorException | IOException e) {
            logger.warn("Could not fetch clipboard contents.", e);
        }

        if (clipboardContents != null) {
            try {
                // Attempt to serialize them to a JsonTree and reset the editor state.
                JsonElement json = new JsonParser().parse(clipboardContents);
                JsonTree node = JsonTreeConverter.serialize(json);
                resetState(node);
            } catch (JsonSyntaxException | NullPointerException e) {
                logger.warn("Could not construct a valid tree from clipboard contents.", e);
            }
        }
    }

    /**
     * Sets the interface focus to the inline editor widget and selects a subset of its' contents.
     *
     * @param node              The node that is currently being edited.
     * @param inlineEditorEntry The inline editor widget.
     */
    protected void focusInlineEditor(JsonTree node, UITextEntry inlineEditorEntry) {
        getManager().setFocus(inlineEditorEntry);
        inlineEditorEntry.resetValue();

        if (node.getValue().getType() == JsonTreeValue.Type.KEY_VALUE_PAIR) {
            // If the node is a key/value pair, select the value of the node.

            if (node.getValue().getValue() instanceof String) {
                inlineEditorEntry.setCursorPosition(node.getValue().getKey().length() + "\"\":\"".length(), true);
                inlineEditorEntry.setCursorPosition(inlineEditorEntry.getText().length() - "\"".length(), false);
            } else {
                inlineEditorEntry.setCursorPosition(node.getValue().getKey().length() + "\"\":".length(), true);
                inlineEditorEntry.setCursorPosition(inlineEditorEntry.getText().length(), false);
            }
        } else {
            // Otherwise fully select the contents of the node.

            inlineEditorEntry.setCursorPosition(0, true);
            inlineEditorEntry.setCursorPosition(inlineEditorEntry.getText().length(), false);
        }
    }

    protected void setEditorSystem(AbstractEditorSystem editorSystem) {
        this.editorSystem = editorSystem;
    }

    protected JsonEditorTreeView getEditor() {
        return this.editor;
    }

    protected void setEditor(JsonEditorTreeView editor) {
        this.editor = editor;
    }

    protected boolean areUnsavedChangesPresent() {
        return unsavedChangesPresent;
    }

    protected void setUnsavedChangesPresent(boolean unsavedChangesPresent) {
        this.unsavedChangesPresent = unsavedChangesPresent;
    }
}
