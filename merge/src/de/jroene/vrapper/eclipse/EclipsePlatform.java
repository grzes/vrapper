package de.jroene.vrapper.eclipse;

import java.util.HashMap;
import java.util.Map;

import kg.totality.core.ui.UIUtils;
import kg.totality.core.utils.CaretType;
import newpackage.glue.HistoryService;

import org.eclipse.jface.action.IStatusLineManager;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.IDocument;
import org.eclipse.jface.text.IRegion;
import org.eclipse.jface.text.ITextOperationTarget;
import org.eclipse.jface.text.ITextViewer;
import org.eclipse.jface.text.ITextViewerExtension5;
import org.eclipse.jface.text.ITextViewerExtension6;
import org.eclipse.jface.text.IUndoManager;
import org.eclipse.jface.text.Position;
import org.eclipse.jface.text.TextSelection;
import org.eclipse.jface.text.source.ISourceViewer;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.GC;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.widgets.Caret;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.texteditor.AbstractTextEditor;
import org.eclipse.ui.texteditor.StatusLineContributionItem;

import de.jroene.vrapper.vim.LineInformation;
import de.jroene.vrapper.vim.Mark;
import de.jroene.vrapper.vim.Platform;
import de.jroene.vrapper.vim.Search;
import de.jroene.vrapper.vim.SearchResult;
import de.jroene.vrapper.vim.Selection;
import de.jroene.vrapper.vim.Space;
import de.jroene.vrapper.vim.ViewPortInformation;

/**
 * Eclipse specific implementation of {@link Platform}.
 * There is an instance for every editor with vim functionality added.
 *
 * @author Matthias Radig
 */
// TODO: consider splitting into few classes on per-responsibility manner
public class EclipsePlatform implements Platform {

    private static final int MESSAGE_WIDTH = 15;
    private static final String CONTRIBUTION_ITEM_NAME = "VimInputMode";
    private static final String MESSAGE_INSERT_MODE = "-- INSERT --";
    private static final String MESSAGE_VISUAL_MODE = "-- VISUAL --";
    private static final String MESSAGE_NORMAL_MODE = "-- NORMAL --";

    @SuppressWarnings("unused")
    private final IWorkbenchWindow window;
    private final AbstractTextEditor part;
    private final ITextViewer textViewer;
    private final ITextViewerExtension5 textViewerExtension5;
    private final HistoryService undoManager;
    private final StatusLine statusLine;
    private Space space;
    private final int defaultCaretWidth;
    private final StatusLineContributionItem vimInputModeItem;
    @SuppressWarnings("unused")
	private boolean lineWiseSelection;
    private String currentMode;
    private boolean lineWiseMouseSelection;
    private int horizontalPosition;
    private final Map<String, Position> marks;

    public EclipsePlatform(IWorkbenchWindow window, AbstractTextEditor part,
            final ITextViewer textViewer) {
        super();
        this.window = window;
        this.part = part;
        this.textViewer = textViewer;
        this.defaultCaretWidth = textViewer.getTextWidget().getCaret().getSize().x;
        this.textViewerExtension5 = textViewer instanceof ITextViewerExtension5
        ? (ITextViewerExtension5) textViewer
                : null;
        if (textViewer instanceof ITextViewerExtension6) {
            IUndoManager delegate = ((ITextViewerExtension6)textViewer).getUndoManager();
            EclipseHistoryService manager = new EclipseHistoryService(textViewer.getTextWidget(), delegate);
            textViewer.setUndoManager(manager);
            this.undoManager = manager;
        } else {
            this.undoManager = new DummyHistoryService();
        }
        setDefaultSpace();
        statusLine = new StatusLine(textViewer.getTextWidget());
        vimInputModeItem = getContributionItem();
        setStatusLine(MESSAGE_NORMAL_MODE);
        marks = new HashMap<String, Position>();
    }


    public String getText(int index, int length) {
        try {
            switch (space) {
            case MODEL:
                return textViewer.getDocument().get(index, length);
            case VIEW:
                return textViewer.getTextWidget().getText(index, index+length-1);
            default:
                throw new IllegalStateException("unknown space: " + space);
            }
        } catch (BadLocationException e) {
            throw new RuntimeException(e);
        }
    }

    public void replace(int index, int length, String s) {
        checkForModelSpace("replace()");
        try {
            IDocument doc = textViewer.getDocument();
            if(index > doc.getLength()) {
                index = doc.getLength();
            }
            doc.replace(index, length, s);
        } catch (BadLocationException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
    }

    public void setActionLine(String actionLine) {
        // TODO Auto-generated method stub

    }

    public void setCommandLine(String commandLine) {
        statusLine.setContent(commandLine);
    }

    public LineInformation getLineInformation() {
        return getLineInformationOfOffset(getPosition());
    }

    public LineInformation getLineInformation(int line) {
        if(space.equals(Space.VIEW)) {
            line = widgetLine2ModelLine(line);
        }
        try {
            IRegion region = textViewer.getDocument().getLineInformation(line);
            return createLineInformation(line, region);
        } catch (BadLocationException e) {
            throw new RuntimeException(e);
        }

    }

    public LineInformation getLineInformationOfOffset(int offset) {
        int line;
        if(space.equals(Space.VIEW)) {
            line = textViewer.getTextWidget().getLineAtOffset(offset);
        } else {
            try {
                line = textViewer.getDocument().getLineOfOffset(offset);
            } catch (BadLocationException e) {
                throw new RuntimeException(e);
            }
        }
        return getLineInformation(line);
    }

    public int getNumberOfLines() {
        switch(space) {
        case VIEW:
            return textViewer.getTextWidget().getLineCount();
        case MODEL:
            return textViewer.getDocument().getNumberOfLines();
        default:
            throw new IllegalStateException("unknown space: " + space);
        }
    }

    public int getPosition() {
        switch(space) {
        case MODEL:
            return widgetOffset2ModelOffset(textViewer.getTextWidget()
                    .getCaretOffset());
        case VIEW:
            return textViewer.getTextWidget().getCaretOffset();
        default:
            throw new IllegalStateException("unknown space: " + space);
        }
    }

    public void setPosition(int index) {
        int offset;
        switch (space) {
        case MODEL:
            offset = modelOffset2WidgetOffset(index);
            break;
        case VIEW:
            offset = index;
            break;
        default:
            throw new IllegalStateException("unknown space: " + space);
        }
        textViewer.getTextWidget().setCaretOffset(offset);
        textViewer.getTextWidget().showSelection();
    }

    public void toCommandLineMode() {
        statusLine.setEnabled(true);
    }

    public void toInsertMode() {
        GC gc = new GC(textViewer.getTextWidget());
        Caret c = textViewer.getTextWidget().getCaret();
        c.setSize(defaultCaretWidth, gc.getFontMetrics().getHeight());
        statusLine.setEnabled(false);
        lineWiseSelection = lineWiseMouseSelection;
        setStatusLine(MESSAGE_INSERT_MODE);
    }

    public void toNormalMode() {
        Caret c = textViewer.getTextWidget().getCaret();
        GC gc = new GC(textViewer.getTextWidget());
        int width = gc.getFontMetrics().getAverageCharWidth();
        int height = gc.getFontMetrics().getHeight();
        c.setSize(width, height);
        gc.dispose();
        statusLine.setEnabled(false);
        lineWiseSelection = lineWiseMouseSelection;
        setStatusLine(MESSAGE_NORMAL_MODE);
    }

    public void toOperatorPendingMode() {
        Caret c = textViewer.getTextWidget().getCaret();
        GC gc = new GC(textViewer.getTextWidget());
        int width = gc.getFontMetrics().getAverageCharWidth();
        int height = gc.getFontMetrics().getHeight()/2;
        c.setSize(width, height);
        gc.dispose();
    }

    public void toVisualMode() {
        Caret c = textViewer.getTextWidget().getCaret();
        c.setSize(1, c.getSize().y);
        lineWiseSelection = false;
        setStatusLine(MESSAGE_VISUAL_MODE);

    }

    public void redo() {
        if(undoManager != null && undoManager.redoable()) {
            undoManager.redo();
        }
    }

    public void undo() {
        if(undoManager != null && undoManager.undoable()) {
            undoManager.undo();
        }
    }

    public void setUndoMark() {
        if(undoManager != null) {
            undoManager.endCompoundChange();
            undoManager.beginCompoundChange();
        }
    }

    public void save() {
        if(part.isDirty()) {
            part.doSave(null);
        }
    }

    public void shift(int line, int lineCount, int shift) {
        if (!space.equals(Space.MODEL)) {
            throw new IllegalStateException("shift cannot be used in view space");
        }
        int op = shift < 0 ? ITextOperationTarget.SHIFT_LEFT : ITextOperationTarget.SHIFT_RIGHT;
        shift = Math.abs(shift);
        int start = getLineInformation(line+lineCount-1).getEndOffset();
        int end = getLineInformation(line).getBeginOffset();
        undoManager.lock();
        setSelection(Selection.fromOffsets(start, end, false));
        for (int i = 0; i < shift; i++) {
            textViewer.getTextOperationTarget().doOperation(op);
        }
        undoManager.unlock();
    }

    public void setSpace(Space space) {
        this.space = space;
    }

    public void setDefaultSpace() {
        space = Space.MODEL;
    }

    public Selection getSelection() {
        Point selectedRange = textViewer.getSelectedRange();
        return new Selection(selectedRange.x, selectedRange.y);
    }

    public void setSelection(Selection s) {
//        if (!space.equals(Space.MODEL)) {
//            throw new IllegalStateException("selection must be set in model space");
//            // XXX what's wrong about: textViewer.getTextWidget().setSelection(...)
//        }
    	switch (space) {
		case MODEL:
	        if (s == null)
				textViewer.getSelectionProvider().setSelection(TextSelection.emptySelection());
			else {
	            //            textViewer.setSelectedRange(s.getStart(), s.getLength());
	            TextSelection ts = new TextSelection(s.getStart(), s.getLength());
	            textViewer.getSelectionProvider().setSelection(ts);
	            lineWiseSelection = s.isLineWise();
	        }
			break;
		case VIEW:
	        if (s == null)
				textViewer.getTextWidget().setSelection(getPosition());
		break;
			default:
	        if (s != null)
	        	textViewer.getTextWidget().setSelection(s.getStart(), s.getLength());
		}
    }

    public void beginChange() {
        undoManager.beginCompoundChange();
        undoManager.lock();
    }

    public void endChange() {
        undoManager.unlock();
        undoManager.endCompoundChange();
    }

    public SearchResult find(Search search, int offset) {
        int position = getPosition();
        if (space.equals(Space.MODEL)) {
            offset = modelOffset2WidgetOffset(offset);
        }
        int index = textViewer.getFindReplaceTarget().findAndSelect(
                offset, search.getKeyword(), !search.isBackward(),
                true, search.isWholeWord());
        if (space.equals(Space.MODEL)) {
            index = widgetOffset2ModelOffset(index);
        }
        // findAndSelect changes position, reset
        setPosition(position);
        return new SearchResult(index);
    }

    public void setRepaint(boolean repaint) {
        textViewer.getTextWidget().setRedraw(repaint);
    }

    public void activate() {
        vimInputModeItem.setText(currentMode);
    }


    public void setLineWiseMouseSelection(boolean lineWise) {
        this.lineWiseMouseSelection = lineWise;
    }

    public boolean close(boolean force) {
        if(force || !part.isDirty()) {
            part.close(false);
            return true;
        }
        return false;
    }

    public void insert(String s) {
        textViewer.getTextWidget().insert(s);
    }

    public ViewPortInformation getViewPortInformation() {
        return new ViewPortInformation(
                textViewer.getTopIndex(),
                textViewer.getBottomIndex());
    }

    public void setTopLine(int number) {
        if (space.equals(Space.MODEL)) {
            throw new IllegalArgumentException("the viewport cannot be changed in model space");
        }
        textViewer.setTopIndex(number);
    }

    public void format(Selection s) {
        if (s != null) {
            setSelection(s);
        }
        textViewer.getTextOperationTarget().doOperation(ISourceViewer.FORMAT);
    }

    private void setStatusLine(String message) {
        vimInputModeItem.setText(message);
        currentMode = message;
    }

    private void checkForModelSpace(String operation) {
        if(!space.equals(Space.MODEL)) {
            throw new IllegalStateException("Operation "+operation+" allowed in model space only");
        }
    }

    private int widgetOffset2ModelOffset(int caretOffset) {
        return textViewerExtension5 != null
        ? textViewerExtension5.widgetOffset2ModelOffset(caretOffset)
                : caretOffset;
    }

    private int modelOffset2WidgetOffset(int index) {
        return textViewerExtension5 != null
        ? textViewerExtension5.modelOffset2WidgetOffset(index)
                : index;
    }

    private int modelLine2WidgetLine(int line) {
        return textViewerExtension5 != null
        ? textViewerExtension5.modelLine2WidgetLine(line)
                : line;
    }

    private int widgetLine2ModelLine(int line) {
        return textViewerExtension5 != null
        ? textViewerExtension5.widgetLine2ModelLine(line)
                : line;
    }

    private LineInformation createLineInformation(int line, IRegion region) {
        switch(space) {
        case MODEL:
            return new LineInformation(line, region.getOffset(), region.getLength());
        case VIEW:
            return new LineInformation(
                    modelLine2WidgetLine(line),
                    modelOffset2WidgetOffset(region.getOffset()),
                    region.getLength());
        default:
            throw new IllegalStateException("unknown space: " + space);
        }
    }

    private StatusLineContributionItem getContributionItem() {
        String name = CONTRIBUTION_ITEM_NAME+part.getEditorSite().getId();
        IStatusLineManager manager = part.getEditorSite().getActionBars().getStatusLineManager();
        StatusLineContributionItem item = (StatusLineContributionItem) manager.find(name);
        if (item == null) {
            item = new StatusLineContributionItem(name, true, MESSAGE_WIDTH);
            try {
                manager.insertBefore("ElementState", item);
            } catch (IllegalArgumentException e) {
                manager.add(item);
            }
        }
        return item;
    }


    public Mark getMark(String name) {
        Position p = marks.get(name);
        if (p == null || p.isDeleted) {
            marks.remove(name);
            return null;
        }
        int offset = p.getOffset();
        if(space.equals(Space.VIEW)) {
            offset = modelOffset2WidgetOffset(offset);
        }
        return new Mark(getLineInformationOfOffset(offset), offset);
    }


    public void setMark(String name) {
        int offset = getPosition();
        if(space.equals(Space.VIEW)) {
            offset = widgetOffset2ModelOffset(offset);
        }
        Position p = new Position(offset);
        try {
            textViewer.getDocument().addPosition(p);
            marks.put(name, p);
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }


	@Override
	public void setCaret(CaretType caretType) {
		// TODO: do it only when caret actually changed
		StyledText styledText = textViewer.getTextWidget();
		Caret caret = UIUtils.createCaret(caretType, styledText);
		styledText.setCaret(caret);
	}


	@Override
	public boolean isEditable() {
		return textViewer.isEditable();
	}


	@Override
	public int getTextLength() {
		switch (space) {
		case VIEW:
			return textViewer.getTextWidget().getCharCount();
		case MODEL:
			return textViewer.getDocument().getLength();
		default:
			throw new IllegalStateException("unknown space: " + space);
		}
	}

	@Override
	public int getHorizontalPosition() {
		return horizontalPosition;
	}

	@Override
	public void updateHorizontalPosition() {
		// FIXME: we have to handle initial tabs
        horizontalPosition = getPosition() - getLineInformation().getBeginOffset();
	}
}