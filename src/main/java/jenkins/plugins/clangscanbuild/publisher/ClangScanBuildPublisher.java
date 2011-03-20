package jenkins.plugins.clangscanbuild.publisher;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.model.Action;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.tasks.BuildStepMonitor;
import hudson.tasks.Recorder;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jenkins.plugins.clangscanbuild.actions.ClangScanBuildAction;
import jenkins.plugins.clangscanbuild.actions.ClangScanBuildProjectAction;
import jenkins.plugins.clangscanbuild.history.ClangScanBuildBug;
import jenkins.plugins.clangscanbuild.history.ClangScanBuildBugSummary;

import org.kohsuke.stapler.DataBoundConstructor;

public class ClangScanBuildPublisher extends Recorder{
	
	private static final Logger LOGGER = Logger.getLogger( ClangScanBuildPublisher.class.getName() );
	
	@Extension
	public static final ClangScanBuildPublisherDescriptor DESCRIPTOR = new ClangScanBuildPublisherDescriptor();

	private static final Pattern BUG_TYPE_PATTERN = Pattern.compile( "<!--\\sBUGTYPE\\s(.*)\\s-->" );
	private static final Pattern BUG_DESC_PATTERN = Pattern.compile( "<!--\\sBUGDESC\\s(.*)\\s-->" );
	private static final Pattern BUGFILE_PATTERN = Pattern.compile( "<!--\\sBUGFILE\\s(.*)\\s-->" );
	private static final Pattern BUGCATEGORY_PATTERN = Pattern.compile( "<!--\\sBUGCATEGORY\\s(.*)\\s-->" );

	private String scanBuildOutputFolder;
	private int bugThreshold;
	private boolean markBuildUnstableWhenThresholdIsExceeded;

	public ClangScanBuildPublisher( 
			String scanBuildOutputFolder,
			boolean markBuildUnstableWhenThresholdIsExceeded, 
			int bugThreshold
			){
		
		super();
		this.scanBuildOutputFolder = scanBuildOutputFolder;
		this.markBuildUnstableWhenThresholdIsExceeded = markBuildUnstableWhenThresholdIsExceeded;
		this.bugThreshold = bugThreshold;
	}
	
	public String getScanBuildOutputFolder() {
		return scanBuildOutputFolder;
	}

	public void setScanBuildOutputFolder(String scanBuildOutputFolder) {
		this.scanBuildOutputFolder = scanBuildOutputFolder;
	}

	public int getBugThreshold() {
		return bugThreshold;
	}
	
	public boolean isMarkBuildUnstableWhenThresholdIsExceeded(){
		return markBuildUnstableWhenThresholdIsExceeded;
	}

	public void setBugThreshold(int bugThreshold) {
		this.bugThreshold = bugThreshold;
	}

	@Override
	public Action getProjectAction( AbstractProject<?, ?> project ){
		return new ClangScanBuildProjectAction( project );
	}

	@Override
	public ClangScanBuildPublisherDescriptor getDescriptor() {
		return DESCRIPTOR;
	}
	
	public BuildStepMonitor getRequiredMonitorService() {
		return BuildStepMonitor.NONE;
	}

	@Override
	public boolean perform( AbstractBuild<?, ?> build, Launcher launcher, BuildListener listener ) throws InterruptedException, IOException {

		listener.getLogger().println( "Publishing Clang scan-build results" );
		
		FilePath workspaceOutputFolder = ensureWorkspaceOutputFolder( build );
			
		FilePath buildOutputFolder = new FilePath( new FilePath( build.getArtifactsDir() ), getScanBuildOutputFolder() );
		
		copyClangReportsToBuildArtifacts( workspaceOutputFolder, buildOutputFolder, listener );

		List<FilePath> clangReports = locateClangBugReports( buildOutputFolder );

		ClangScanBuildBugSummary previousBugSummary = getBugSummaryForLastBuild( build );
		ClangScanBuildBugSummary newBugSummary = new ClangScanBuildBugSummary( build.number );
		
		for( FilePath report : clangReports ){
			ClangScanBuildBug bug = createBugFromClangScanBuildHtml( build.getProject().getName(), report, previousBugSummary, build.getWorkspace().getRemote() );
			newBugSummary.add( bug );
		}
		
		
		FilePath bugSummaryXMLFile = new FilePath( buildOutputFolder, "bugSummary.xml" );
		
		String bugSummaryXML = AbstractBuild.XSTREAM.toXML( newBugSummary );
		bugSummaryXMLFile.write( bugSummaryXML, "UTF-8" );
		

		final ClangScanBuildAction action = new ClangScanBuildAction( build, newBugSummary.getBugCount(), markBuildUnstableWhenThresholdIsExceeded, bugThreshold, scanBuildOutputFolder, bugSummaryXMLFile );
        build.getActions().add( action );

        if( action.buildFailedDueToExceededThreshold() ){
        	listener.getLogger().println( "Clang scan-build threshhold exceeded." );
            build.setResult( Result.UNSTABLE );
        }

		return true;
	}
	
	private ClangScanBuildBug createBugFromClangScanBuildHtml( String projectName, FilePath report, ClangScanBuildBugSummary previousBugSummary, String workspacePath ){
		ClangScanBuildBug bug = createBugInstance( projectName, report, workspacePath );
		if( previousBugSummary != null ){
			// This marks bugs as new if they did not exist in the last build report
			bug.setNewBug( !previousBugSummary.contains( bug ) );
		}
		return bug;
	}

	private FilePath ensureWorkspaceOutputFolder(AbstractBuild<?, ?> build) throws IOException, InterruptedException {
		FilePath workspaceOutputFolder = new FilePath( build.getWorkspace(), getScanBuildOutputFolder() );
		if( !workspaceOutputFolder.exists() ) workspaceOutputFolder.mkdirs();
		return workspaceOutputFolder;
	}

	private ClangScanBuildBugSummary getBugSummaryForLastBuild( AbstractBuild<?, ?> build) {
		if( build.getPreviousBuild() != null ){
			ClangScanBuildAction previousAction = build.getPreviousBuild().getAction( ClangScanBuildAction.class );
			if( previousAction != null ){
				return previousAction.loadBugSummary();
			}
		}
		return null;
	}

	/**
	 * Clang always creates a subfolder within the specified output folder that has a unique name.
	 * This method locates the first subfolder of the output folder and copies its contents
	 * to the build archive folder.
	 */
	private void copyClangReportsToBuildArtifacts( FilePath workspaceOutputFolder, FilePath buildOutputFolder, BuildListener listener ){
		try{
			List<FilePath> subFolders = workspaceOutputFolder.listDirectories();
			if( subFolders.isEmpty() ){
				listener.getLogger().println( "Could not locate a unique scan-build output folder in: " + workspaceOutputFolder );
				return;
			}
	
			FilePath clangDateFolder = workspaceOutputFolder.listDirectories().get( 0 );
			clangDateFolder.copyRecursiveTo( buildOutputFolder );
		}catch( Exception e ){
			listener.fatalError( "Unable to copy Clan scan-build output to build archive folder." );
		}
	}

	private ClangScanBuildBug createBugInstance( String projectName, FilePath report, String workspacePath ){
		ClangScanBuildBug instance = new ClangScanBuildBug();
		instance.setReportFile( report.getName() );
		
		String contents = null;
		try {
			contents = report.readToString();
			instance.setBugDescription( getMatch( BUG_DESC_PATTERN, contents ) );
			instance.setBugType( getMatch( BUG_TYPE_PATTERN, contents ) );
			instance.setBugCategory( getMatch( BUGCATEGORY_PATTERN, contents ) );
			String sourceFile = getMatch( BUGFILE_PATTERN, contents );

			// This attempts to shorten the file path by removing the workspace path and
			// leaving only the path relative to the workspace.
			int position = sourceFile.lastIndexOf( workspacePath );
			if( position >= 0 ){
				sourceFile = sourceFile.substring( position + workspacePath.length() );
			}
			
			instance.setSourceFile( sourceFile );
		}catch( IOException e ){
			LOGGER.log( Level.ALL, "Unable to read file or locate clang markers in content: " + report );
		}

		return instance;
	}

	private String getMatch( Pattern pattern, String contents ){
		Matcher matcher = pattern.matcher( contents );
		while( matcher.find() ){
			return matcher.group(1);
		}
		return null;
	}
	
	protected List<FilePath> locateClangBugReports( FilePath clangOutputFolder ) throws IOException, InterruptedException {
        List<FilePath> files = new ArrayList<FilePath>();
        files.addAll( Arrays.asList( clangOutputFolder.list( "**/report-*.html" ) ) );
        return files;
	}
	
}