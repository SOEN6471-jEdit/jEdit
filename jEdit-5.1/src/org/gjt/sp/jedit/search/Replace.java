package org.gjt.sp.jedit.search;

import java.awt.Component;

import org.gjt.sp.jedit.BeanShell;
import org.gjt.sp.jedit.Buffer;
import org.gjt.sp.jedit.GUIUtilities;
import org.gjt.sp.jedit.TextUtilities;
import org.gjt.sp.jedit.View;
import org.gjt.sp.jedit.jEdit;
import org.gjt.sp.jedit.bsh.BshMethod;
import org.gjt.sp.jedit.bsh.NameSpace;
import org.gjt.sp.jedit.buffer.JEditBuffer;
import org.gjt.sp.jedit.textarea.JEditTextArea;
import org.gjt.sp.jedit.textarea.Selection;
import org.gjt.sp.jedit.textarea.TextArea;
import org.gjt.sp.util.TaskManager;

public class Replace {

	private static BshMethod replaceMethod;
	private static NameSpace replaceNS = new NameSpace(
		BeanShell.getNameSpace(),
		BeanShell.getNameSpace().getClassManager(),
		"search and replace");
	
	//{{{ initReplace() method
	/**
	 * Set up BeanShell replace if necessary.
	 */
	private static void initReplace() throws Exception
	{
		String replace = Search.getReplaceString();
		if(Search.getBeanShellReplace() && replace.length() != 0)
		{
			String text;
			if( replace.trim().startsWith( "{" ) )
				text = replace;
			else
				text = "return (" + replace + ");";	
			replaceMethod = BeanShell.cacheBlock("replace",
				text,true);
		}
		else
			replaceMethod = null;
	} //}}}
	
	//{{{ replaceInSelection() method
	private static int replaceInSelection(View view, TextArea textArea,
		Buffer buffer, SearchMatcher matcher, boolean smartCaseReplace,
		Selection s) throws Exception
	{
		/* if an occurence occurs at the
		beginning of the selection, the
		selection start will get moved.
		this sucks, so we hack to avoid it. */
		int start = s.getStart();

		int returnValue;

		if(s instanceof Selection.Range)
		{
			returnValue = _replace(view,buffer,matcher,
				s.getStart(),s.getEnd(),
				smartCaseReplace);

			textArea.removeFromSelection(s);
			textArea.addToSelection(new Selection.Range(
				start,s.getEnd()));
		}
		else if(s instanceof Selection.Rect)
		{
			Selection.Rect rect = (Selection.Rect)s;
			int startCol = rect.getStartColumn(
				buffer);
			int endCol = rect.getEndColumn(
				buffer);

			returnValue = 0;
			for(int j = s.getStartLine(); j <= s.getEndLine(); j++)
			{
				returnValue += _replace(view,buffer,matcher,
					getColumnOnOtherLine(buffer,j,startCol),
					getColumnOnOtherLine(buffer,j,endCol),
					smartCaseReplace);
			}
			textArea.addToSelection(new Selection.Rect(
				start,s.getEnd()));
		}
		else
			throw new RuntimeException("Unsupported: " + s);

		return returnValue;
	} //}}}

	//{{{ _replace() method
	/**
	 * Replaces all occurrences of the search string with the replacement
	 * string.
	 * @param view The view
	 * @param buffer The buffer
	 * @param start The start offset
	 * @param end The end offset
	 * @param matcher The search matcher to use
	 * @param smartCaseReplace See user's guide
	 * @return The number of occurrences replaced
	 */
	private static int _replace(View view, JEditBuffer buffer,
		SearchMatcher matcher, int start, int end,
		boolean smartCaseReplace)
		throws Exception
	{
		if(matcher.wholeWord)
		{
			String noWordSep = buffer.getStringProperty("noWordSep");
			matcher.setNoWordSep(noWordSep);
		}
		int occurCount = 0;

		boolean endOfLine = (buffer.getLineEndOffset(
			buffer.getLineOfOffset(end)) - 1 == end);

		int offset = start;
loop:	for(int counter = 0; ; counter++)
		{
			boolean startOfLine = (buffer.getLineStartOffset(
				buffer.getLineOfOffset(offset)) == offset);

			CharSequence text = buffer.getSegment(offset,end - offset);
			SearchMatcher.Match occur = matcher.nextMatch(
				text,startOfLine,endOfLine,counter == 0,false);
			if(occur == null)
				break loop;

			CharSequence found = text.subSequence(
				occur.start, occur.end);

			int length = replaceOne(view,buffer,occur,offset,
				found,smartCaseReplace);
			if(length == -1)
				offset += occur.end;
			else
			{
				offset += occur.start + length;
				end += (length - found.length());
				occurCount++;
			}
		}

		return occurCount;
	} //}}}

	//{{{ replaceOne() method
	/**
	 * Replace one occurrence of the search string with the
	 * replacement string.
	 */
	private static int replaceOne(View view, JEditBuffer buffer,
		SearchMatcher.Match occur, int offset, CharSequence found,
		boolean smartCaseReplace)
		throws Exception
	{
		String subst = replaceOne(view,buffer,occur,found);
		if(smartCaseReplace && Search.getIgnoreCase())
		{
			int strCase = TextUtilities.getStringCase(found);
			if(strCase == TextUtilities.LOWER_CASE)
				subst = subst.toLowerCase();
			else if(strCase == TextUtilities.UPPER_CASE)
				subst = subst.toUpperCase();
			else if(strCase == TextUtilities.TITLE_CASE)
				subst = TextUtilities.toTitleCase(subst);
		}

		if(subst != null)
		{
			int start = offset + occur.start;
			int end = offset + occur.end;

			if (end - start > 0)
				buffer.remove(start,end - start);
			buffer.insert(start,subst);
			return subst.length();
		}
		else
			return -1;
	} //}}}

	//{{{ replaceOne() method
	private static String replaceOne(View view, JEditBuffer buffer,
		SearchMatcher.Match occur, CharSequence found)
		throws Exception
	{
		if(Search.getRegexp())
		{
			if(replaceMethod != null)
				return regexpBeanShellReplace(view,buffer,occur);
			else
				return regexpReplace(occur,found);
		}
		else
		{
			if(replaceMethod != null)
				return literalBeanShellReplace(view,buffer,found);
			else
				return Search.getReplaceString();
		}
	} //}}}

	//{{{ regexpBeanShellReplace() method
	private static String regexpBeanShellReplace(View view,
		JEditBuffer buffer, SearchMatcher.Match occur) throws Exception
	{
		replaceNS.setVariable("buffer", buffer, false);
		for(int i = 0; i < occur.substitutions.length; i++)
		{
			replaceNS.setVariable("_" + i,
				occur.substitutions[i]);
		}

		Object obj = BeanShell.runCachedBlock(
			replaceMethod,view,replaceNS);

		for(int i = 0; i < occur.substitutions.length; i++)
		{
			replaceNS.setVariable("_" + i,
				null, false);
		}
		// Not really necessary because it is already cleared in the end of
		// BeanShell.runCachedBlock()
		replaceNS.setVariable("buffer", null, false);

		if(obj == null)
			return "";
		else
			return obj.toString();
	} //}}}

	//{{{ regexpReplace() method
	private static String regexpReplace(SearchMatcher.Match occur,
		CharSequence found) throws Exception
	{
		StringBuilder buf = new StringBuilder();
		String replace = Search.getReplaceString();

		for(int i = 0; i < replace.length(); i++)
		{
			char ch = replace.charAt(i);
			switch(ch)
			{
			case '$':
				if(i == replace.length() - 1)
				{
					// last character of the replace string, 
					// it is not a capturing group
					buf.append(ch);
					break;
				}

				ch = replace.charAt(++i);
				if(ch == '$')
				{
					// It was $$, so it is an escaped $
					buf.append('$');
				}
				else if(ch == '0')
				{
					// $0 meaning the first capturing group :
					// the found value
					buf.append(found);
				}
				else if(Character.isDigit(ch))
				{
					int n = ch - '0';
					while (i < replace.length() - 1)
					{
						ch = replace.charAt(++i);
						if (Character.isDigit(ch))
						{
							n = n * 10 + (ch - '0');
						}
						else
						{
							// The character is not 
							// a digit, going back and
							// end loop
							i--;
							break;
						}
					}
					if(n < occur
						.substitutions
						.length)
					{
						String subs = occur.substitutions[n];
						if (subs != null)
							buf.append(subs);
					}
				}
				break;
			case '\\':
				if(i == replace.length() - 1)
				{
					buf.append('\\');
					break;
				}
				ch = replace.charAt(++i);
				switch(ch)
				{
				case 'n':
					buf.append('\n');
					break;
				case 't':
					buf.append('\t');
					break;
				default:
					buf.append(ch);
					break;
				}
				break;
			default:
				buf.append(ch);
				break;
			}
		}

		return buf.toString();
	} //}}}	

	//{{{ literalBeanShellReplace() method
	private static String literalBeanShellReplace(View view,
		JEditBuffer buffer, CharSequence found)
		throws Exception
	{
		replaceNS.setVariable("buffer",buffer);
		replaceNS.setVariable("_0",found);
		Object obj = BeanShell.runCachedBlock(
			replaceMethod,
			view,replaceNS);

		replaceNS.setVariable("_0", null, false);
		// Not really necessary because it is already cleared in the end of
		// BeanShell.runCachedBlock()
		replaceNS.setVariable("buffer", null, false);

		if(obj == null)
			return "";
		else
			return obj.toString();
	} //}}}
	
	//{{{ getColumnOnOtherLine() method
	/**
	 * Should be somewhere else...
	 */
	private static int getColumnOnOtherLine(Buffer buffer, int line,
		int col)
	{
		int returnValue = buffer.getOffsetOfVirtualColumn(
			line,col,null);
		if(returnValue == -1)
			return buffer.getLineEndOffset(line) - 1;
		else
			return buffer.getLineStartOffset(line) + returnValue;
	} //}}}

	//{{{ replace() method
	/**
	 * Replaces the current selection with the replacement string.
	 * @param view The view
	 * @return True if the operation was successful, false otherwise
	 */
	public static boolean replace(View view)
	{
		// component that will parent any dialog boxes
		Component comp = SearchDialog.getSearchDialog(view);
		if(comp == null)
			comp = view;
	
		JEditTextArea textArea = view.getTextArea();
	
		Buffer buffer = view.getBuffer();
		if(!buffer.isEditable())
			return false;
	
		boolean smartCaseReplace = Search.getSmartCaseReplace();
	
		Selection[] selection = textArea.getSelection();
		if (selection.length == 0)
		{
			view.getToolkit().beep();
			return false;
		}
	
		Search.record(view,"replace(view)",true,false);
	
		// a little hack for reverse replace and find
		int caret = textArea.getCaretPosition();
		Selection s = textArea.getSelectionAtOffset(caret);
		if(s != null)
			caret = s.getStart();
	
		try
		{
			buffer.beginCompoundEdit();
	
			SearchMatcher matcher = Search.getSearchMatcher();
			if(matcher == null)
				return false;
	
			initReplace();
	
			int retVal = 0;
	
			for(int i = 0; i < selection.length; i++)
			{
				s = selection[i];
	
				retVal += replaceInSelection(view,textArea,
					buffer,matcher,smartCaseReplace,s);
			}
			
			if(Search.getReverseSearch())
			{
				// so that Replace and Find continues from
				// the right location
				textArea.moveCaretPosition(caret);
			}
			else
			{
				s = textArea.getSelectionAtOffset(
					textArea.getCaretPosition());
				if(s != null)
					textArea.moveCaretPosition(s.getEnd());
			}
	
			if(!BeanShell.isScriptRunning())
			{
				Object[] args = {Integer.valueOf(retVal),
				                 Integer.valueOf(1)};
				view.getStatus().setMessageAndClear(jEdit.getProperty(
					"view.status.replace-all",args));
			}
	
			if(retVal == 0)
			{
				view.getToolkit().beep();
				return false;
			}
	
			return true;
		}
		catch(Exception e)
		{
			Search.handleError(comp,e);
		}
		finally
		{
			buffer.endCompoundEdit();
		}
	
		return false;
	} //}}}

	//{{{ replaceAll() method
	/**
	 * Replaces all occurrences of the search string with the replacement
	 * string.
	 * @param view The view
	 * @param dontOpenChangedFiles Whether to open changed files or to autosave them quietly
	 * @return the number of modified files
	 */
	public static boolean replaceAll(View view, boolean dontOpenChangedFiles)
	{
		// component that will parent any dialog boxes
		Component comp = SearchDialog.getSearchDialog(view);
		if(comp == null)
			comp = view;

		SearchFileSet fileset = Search.getSearchFileSet();
		if(fileset.getFileCount(view) == 0)
		{
			GUIUtilities.error(comp,"empty-fileset",null);
			return false;
		}

		Search.record(view,"replaceAll(view)",true,true);

		view.showWaitCursor();

		boolean smartCaseReplace = Search.getSmartCaseReplace();

		int fileCount = 0;
		int occurCount = 0;
		try
		{
			SearchMatcher matcher = Search.getSearchMatcher();
			if(matcher == null)
				return false;

			initReplace();

			String path = fileset.getFirstFile(view);
loop:		while(path != null)
			{
				Buffer buffer = jEdit.openTemporary(
					view,null,path,false);

				/* this is stupid and misleading.
				 * but 'path' is not used anywhere except
				 * the above line, and if this is done
				 * after the 'continue', then we will
				 * either hang, or be forced to duplicate
				 * it inside the buffer == null, or add
				 * a 'finally' clause. you decide which one's
				 * worse. */
				path = fileset.getNextFile(view,path);

				if(buffer == null)
					continue loop;

				// Wait for buffer to finish loading
				if(buffer.isPerformingIO())
					TaskManager.instance.waitForIoTasks();

				if(!buffer.isEditable())
					continue loop;

				// Leave buffer in a consistent state if
				// an error occurs
				int retVal = 0;

				try
				{
					buffer.beginCompoundEdit();
					retVal = _replace(view,buffer,matcher,
						0,buffer.getLength(),
						smartCaseReplace);
				}
				finally
				{
					buffer.endCompoundEdit();
				}

				if(retVal != 0)
				{
					fileCount++;
					occurCount += retVal;
					if (dontOpenChangedFiles)
					{
						buffer.save(null,null);
					}
					else
					{
						jEdit.commitTemporary(buffer);
						jEdit.getBufferSetManager().addBuffer(view, buffer);
					}
				}
			}
		}
		catch(Exception e)
		{
			Search.handleError(comp,e);
		}
		finally
		{
			view.hideWaitCursor();
		}

		/* Don't do this when playing a macro, cos it's annoying */
		if(!BeanShell.isScriptRunning())
		{
			Object[] args = {Integer.valueOf(occurCount),
			                 Integer.valueOf(fileCount)};
			view.getStatus().setMessageAndClear(jEdit.getProperty(
				"view.status.replace-all",args));
			if(occurCount == 0)
				view.getToolkit().beep();
		}

		return (fileCount != 0);
	} //}}}
}
