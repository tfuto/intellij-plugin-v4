package org.antlr.intellij.plugin;

import com.intellij.execution.filters.TextConsoleBuilder;
import com.intellij.execution.filters.TextConsoleBuilderFactory;
import com.intellij.execution.ui.ConsoleView;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.changes.BackgroundFromStartOption;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.vfs.VirtualFileAdapter;
import com.intellij.openapi.vfs.VirtualFileEvent;
import com.intellij.openapi.vfs.VirtualFileManager;
import org.antlr.intellij.plugin.actions.RunANTLROnGrammarFile;
import org.antlr.intellij.plugin.preview.PreviewPanel;
import org.antlr.v4.Tool;
import org.antlr.v4.parse.ANTLRParser;
import org.antlr.v4.runtime.ANTLRInputStream;
import org.antlr.v4.runtime.ConsoleErrorListener;
import org.antlr.v4.runtime.LexerInterpreter;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.misc.Nullable;
import org.antlr.v4.tool.ANTLRMessage;
import org.antlr.v4.tool.DefaultToolListener;
import org.antlr.v4.tool.Grammar;
import org.antlr.v4.tool.LexerGrammar;
import org.antlr.v4.tool.ast.GrammarRootAST;
import org.jetbrains.annotations.NotNull;
import org.stringtemplate.v4.ST;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** This object acts like the controller for the ANTLR plug-in. It receives
 *  events and can send them on to its contained components. For example,
 *  saving the grammar editor or flipping to a new grammar sends an event
 *  to this object, which forwards on update events to the preview tool window.
 *
 *  The main components are a console tool window forever output and
 *  the main panel of the preview tool window.
 */
public class ANTLRv4ProjectComponent implements ProjectComponent {
	public static final Logger LOG = Logger.getInstance("org.antlr.intellij.plugin.ANTLRv4ProjectComponent");

	public Project project;
	public PreviewPanel previewPanel;
	public ConsoleView console;

	public ANTLRv4ProjectComponent(Project project) {
		this.project = project;
	}

	public static ANTLRv4ProjectComponent getInstance(Project project) {
		ANTLRv4ProjectComponent pc = project.getComponent(ANTLRv4ProjectComponent.class);
		return pc;
	}

/* doesn't work if file is not in a source dir of a project i think.
	public static Project getProjectForFile(VirtualFile virtualFile) {
		Project[] openProjects = ProjectManager.getInstance().getOpenProjects();
		Project project = null;
		for (int i = 0; i < openProjects.length; i++) {
			Project p = openProjects[i];
			ProjectFileIndex fileIndex = ProjectRootManager.getInstance(p).getFileIndex();
			if ( fileIndex.isInContent(virtualFile) ) {
				project = p;
			}
		}
		return project;
	}
	 */

	public PreviewPanel getTreeViewPanel() {
		return previewPanel;
	}

	public ConsoleView getConsole() {
		return console;
	}

	// -------------------------------------

	@Override
	public void initComponent() {
		previewPanel = new PreviewPanel();
		TextConsoleBuilderFactory consoleBuidlerFactory = TextConsoleBuilderFactory.getInstance();
		TextConsoleBuilder consoleBuilder = consoleBuidlerFactory.createBuilder(project);

		console = consoleBuilder.getConsole();

		VirtualFileManager.getInstance().addVirtualFileListener(new VirtualFileAdapter() {
			@Override
			public void contentsChanged(VirtualFileEvent event) {
				final VirtualFile vfile = event.getFile();
				if ( !vfile.getName().endsWith(".g4") ) return;

				//System.out.println("save "+vfile.getName());
				ApplicationManager.getApplication().invokeLater(
					new Runnable() {
						@Override
						public void run() {
							String title = "ANTLR Code Generation";
							boolean canBeCancelled = true;
							Task.Backgroundable gen =
								new RunANTLROnGrammarFile(new VirtualFile[]{vfile},
														  project,
														  title,
														  canBeCancelled,
														  new BackgroundFromStartOption());
							ProgressManager.getInstance().run(gen);

						}
					}
															   );
			}
		});
	}

	@Override
	public void projectOpened() {
	}

	@Override
	public void projectClosed() {
	}

	@Override
	public void disposeComponent() {
	}

	@NotNull
	@Override
	public String getComponentName() {
		return "antlr.ProjectComponent";
	}

	public static Object[] parseText(PreviewPanel PreviewPanel,
									 String inputText,
									 String grammarFileName,
									 String startRule)
		throws IOException
	{
		if (!new File(grammarFileName).exists()) {
			return null;
		}

		Tool antlr = new Tool();
		antlr.errMgr = new PluginIgnoreMissingTokensFileErrorManager(antlr);
		antlr.errMgr.setFormat("antlr");
		MyANTLRToolListener listener = new MyANTLRToolListener(antlr);
		antlr.addListener(listener);

		String combinedGrammarFileName = null;
		String lexerGrammarFileName = null;
		String parserGrammarFileName = null;

		Grammar g = antlr.loadGrammar(grammarFileName); // load to examine it
		// examine's Grammar AST from v4 itself;
		// hence use ANTLRParser.X not ANTLRv4Parser from this plugin
		switch ( g.getType() ) {
			case ANTLRParser.PARSER :
				parserGrammarFileName = grammarFileName;
				int i = grammarFileName.indexOf("Parser");
				if ( i>=0 ) {
					lexerGrammarFileName = grammarFileName.substring(0, i) + "Lexer.g4";
				}
				break;
			case ANTLRParser.LEXER :
				lexerGrammarFileName = grammarFileName;
				int i2 = grammarFileName.indexOf("Lexer");
				if ( i2>=0 ) {
					parserGrammarFileName = grammarFileName.substring(0, i2) + "Parser.g4";
				}
				break;
			case ANTLRParser.COMBINED :
				combinedGrammarFileName = grammarFileName;
				lexerGrammarFileName = grammarFileName+"Lexer";
				parserGrammarFileName = grammarFileName+"Parser";
				break;
		}

		if ( lexerGrammarFileName==null ) {
			LOG.error("Can't compute lexer file name from "+grammarFileName, (Throwable)null);
			return null;
		}
		if ( parserGrammarFileName==null ) {
			LOG.error("Can't compute parser file name from "+grammarFileName, (Throwable)null);
			return null;
		}

		ANTLRInputStream input = new ANTLRInputStream(inputText);
		LexerInterpreter lexEngine;
		if ( combinedGrammarFileName!=null ) {
			// already loaded above
			if ( listener.grammarErrorMessage!=null ) {
				return null;
			}
			lexEngine = g.createLexerInterpreter(input);
		}
		else {
			LexerGrammar lg = null;
			try {
				lg = (LexerGrammar)Grammar.load(lexerGrammarFileName);
			}
			catch (ClassCastException cce) {
				LOG.error("File " + lexerGrammarFileName + " isn't a lexer grammar", cce);
			}
			if ( listener.grammarErrorMessage!=null ) {
				return null;
			}
			g = loadGrammar(antlr, parserGrammarFileName, lg);
			lexEngine = lg.createLexerInterpreter(input);
		}

//		final JTextArea console = PreviewPanel.getConsole();
//		final MyConsoleErrorListener syntaxErrorListener = new MyConsoleErrorListener();
//
//		CommonTokenStream tokens = new CommonTokenStream(lexEngine);
//		ParserInterpreter parser = g.createParserInterpreter(tokens);
//		parser.removeErrorListeners();
//		parser.addErrorListener(syntaxErrorListener);
//		lexEngine.removeErrorListeners();
//		lexEngine.addErrorListener(syntaxErrorListener);
//		Rule start = g.getRule(startRule);
//		if ( start==null ) {
//			return null; // can't find start rule
//		}
//		ParseTree t = parser.parse(start.index);
//
//		console.setText(Utils.join(syntaxErrorListener.syntaxErrors.iterator(), "\n"));
//		if ( t!=null ) {
//			return new Object[] {parser, t};
//		}
		return null;
	}

	/** Get lexer and parser grammars */
	public static Grammar[] loadGrammars(String grammarFileName) {
		Tool antlr = new Tool();
		antlr.errMgr = new PluginIgnoreMissingTokensFileErrorManager(antlr);
		antlr.errMgr.setFormat("antlr");
		MyANTLRToolListener listener = new MyANTLRToolListener(antlr);
		antlr.addListener(listener);

		String combinedGrammarFileName = null;
		String lexerGrammarFileName = null;
		String parserGrammarFileName = null;

		Grammar g = antlr.loadGrammar(grammarFileName); // load to examine it
		// examine's Grammar AST from v4 itself;
		// hence use ANTLRParser.X not ANTLRv4Parser from this plugin
		switch ( g.getType() ) {
			case ANTLRParser.PARSER :
				parserGrammarFileName = grammarFileName;
				int i = grammarFileName.indexOf("Parser");
				if ( i>=0 ) {
					lexerGrammarFileName = grammarFileName.substring(0, i) + "Lexer.g4";
				}
				break;
			case ANTLRParser.LEXER :
				lexerGrammarFileName = grammarFileName;
				int i2 = grammarFileName.indexOf("Lexer");
				if ( i2>=0 ) {
					parserGrammarFileName = grammarFileName.substring(0, i2) + "Parser.g4";
				}
				break;
			case ANTLRParser.COMBINED :
				combinedGrammarFileName = grammarFileName;
				lexerGrammarFileName = grammarFileName+"Lexer";
				parserGrammarFileName = grammarFileName+"Parser";
				break;
		}

		if ( lexerGrammarFileName==null ) {
			LOG.error("Can't compute lexer file name from "+grammarFileName, (Throwable)null);
			return null;
		}
		if ( parserGrammarFileName==null ) {
			LOG.error("Can't compute parser file name from "+grammarFileName, (Throwable)null);
			return null;
		}

		LexerGrammar lg = null;

		if ( combinedGrammarFileName!=null ) {
			// already loaded above
			lg = g.getImplicitLexer();
			if ( listener.grammarErrorMessage!=null ) {
				g = null;
			}
		}
		else {
			try {
				lg = (LexerGrammar)Grammar.load(lexerGrammarFileName);
			}
			catch (ClassCastException cce) {
				LOG.error("File " + lexerGrammarFileName + " isn't a lexer grammar", cce);
				lg = null;
			}
			if ( listener.grammarErrorMessage!=null ) {
				lg = null;
			}
			g = loadGrammar(antlr, parserGrammarFileName, lg);
		}

		return new Grammar[] {lg, g};
	}

	/** Same as loadGrammar(fileName) except import vocab from existing lexer */
	public static Grammar loadGrammar(Tool tool, String fileName, LexerGrammar lexerGrammar) {
		GrammarRootAST grammarRootAST = tool.parseGrammar(fileName);
		final Grammar g = tool.createGrammar(grammarRootAST);
		g.fileName = fileName;
		g.importVocab(lexerGrammar);
		tool.process(g, false);
		return g;
	}

	static class MyANTLRToolListener extends DefaultToolListener {
		public String grammarErrorMessage;
		public MyANTLRToolListener(Tool tool) { super(tool); }

		@Override
		public void error(ANTLRMessage msg) {
//			super.error(msg);
			ST msgST = tool.errMgr.getMessageTemplate(msg);
			grammarErrorMessage = msgST.render();
			if (tool.errMgr.formatWantsSingleLineMessage()) {
				grammarErrorMessage = grammarErrorMessage.replace('\n', ' ');
			}
		}
	}

	/** Traps parser interpreter syntax errors */
	static class MyConsoleErrorListener extends ConsoleErrorListener {
		public List<String> syntaxErrors = new ArrayList<String>();
		@Override
		public void syntaxError(Recognizer<?, ?> recognizer,
								@Nullable Object offendingSymbol,
								int line, int charPositionInLine, String msg,
								@Nullable RecognitionException e)
		{
//			super.syntaxError(recognizer, offendingSymbol, line, charPositionInLine, msg, e);
			syntaxErrors.add("line " + line + ":" + charPositionInLine + " " + msg);
		}
	}
}
