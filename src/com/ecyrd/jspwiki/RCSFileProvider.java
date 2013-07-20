/* 
    JSPWiki - a JSP-based WikiWiki clone.

    Copyright (C) 2001 Janne Jalkanen (Janne.Jalkanen@iki.fi)

    This program is free software; you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation; either version 2 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program; if not, write to the Free Software
    Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.ecyrd.jspwiki;

import java.util.Properties;
import org.apache.log4j.Category;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import org.apache.oro.text.*;
import org.apache.oro.text.regex.*;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Date;
import java.text.SimpleDateFormat;

/**
 *  This class implements a simple RCS file provider.  NOTE: You MUST
 *  have the RCS package installed for this to work.  They must also
 *  be in your path...
 *
 *  <P>
 *  The RCS file provider extends from the FileSystemProvider, which
 *  means that it provides the pages in the same way.  The only difference
 *  is that it implements the version history commands, and also in each
 *  checkin it writes the page to the RCS repository as well.
 *
 *  @author Janne Jalkanen
 */
// FIXME: Not all commands read their format from the property file yet.
public class RCSFileProvider
    extends FileSystemProvider
{
    private String m_checkinCommand  = "ci -q -mx -l -t-none %s";
    private String m_checkoutCommand = "co -l %s";
    private String m_logCommand      = "rlog -h %s";
    private String m_fullLogCommand  = "rlog %s";
    private String m_checkoutVersionCommand = "co -p -r1.%v %s";
    
    private static final Category   log = Category.getInstance(RCSFileProvider.class);

    public static final String    PROP_CHECKIN  = "jspwiki.rcsFileProvider.checkinCommand";
    public static final String    PROP_CHECKOUT = "jspwiki.rcsFileProvider.checkoutCommand";
    public static final String    PROP_LOG      = "jspwiki.rcsFileProvider.logCommand";
    public static final String    PROP_FULLLOG  = "jspwiki.rcsFileProvider.fullLogCommand";
    public static final String    PROP_CHECKOUTVERSION = "jspwiki.rcsFileProvider.checkoutVersionCommand";
    
    public void initialize( Properties props )
        throws NoRequiredPropertyException
    {
        log.debug("Initing RCS");
        super.initialize( props );

        m_checkinCommand = props.getProperty( PROP_CHECKIN, m_checkinCommand );
        m_checkoutCommand = props.getProperty( PROP_CHECKOUT, m_checkoutCommand );
        m_logCommand     = props.getProperty( PROP_LOG, m_logCommand );
        m_fullLogCommand = props.getProperty( PROP_FULLLOG, m_fullLogCommand );
        m_checkoutVersionCommand = props.getProperty( PROP_CHECKOUTVERSION, m_checkoutVersionCommand );
        
        File rcsdir = new File( getPageDirectory(), "RCS" );

        if( !rcsdir.exists() )
            rcsdir.mkdirs();

        log.debug("checkin="+m_checkinCommand);
        log.debug("checkout="+m_checkoutCommand);
        log.debug("log="+m_logCommand);
        log.debug("fulllog="+m_fullLogCommand);
        log.debug("checkoutversion="+m_checkoutVersionCommand);
    }

    public WikiPage getPageInfo( String page )
    {
        WikiPage info = super.getPageInfo( page );

        try
        {
            String   cmd = m_logCommand;
            String[] env = new String[0];

            cmd = TranslatorReader.replaceString( cmd, "%s", mangleName(page)+FILE_EXT );
            log.debug("Command = '"+cmd+"'");

            Process process = Runtime.getRuntime().exec( cmd, env, new File(getPageDirectory()) );

            BufferedReader stdout = new BufferedReader( new InputStreamReader(process.getInputStream()) );

            String line;

            // FIXME: Use ORO for this, too.
            while( (line = stdout.readLine()) != null )
            {
                if( line.startsWith( "head:" ) )
                {
                    int cutpoint = line.lastIndexOf('.');

                    String version = line.substring( cutpoint+1 );

                    int vernum = Integer.parseInt( version );

                    info.setVersion( vernum );

                    break;
                }
            }

            process.waitFor();

        }
        catch( Exception e )
        {
            log.warn("Failed to read RCS info",e);
        }

        return info;
    }

    public String getPageText( String page, int version )
    {
        StringBuffer result = new StringBuffer();

        log.debug("Fetching specific version "+version+" of page "+page);
        try
        {
            String cmd = m_checkoutVersionCommand;
            String[] env = new String[0];

            cmd = TranslatorReader.replaceString( cmd, "%s", mangleName(page)+FILE_EXT );
            cmd = TranslatorReader.replaceString( cmd, "%v", Integer.toString(version ) );

            log.debug("Command = '"+cmd+"'");

            Process process = Runtime.getRuntime().exec( cmd, env, new File(getPageDirectory()) );

            BufferedReader stdout = new BufferedReader( new InputStreamReader(process.getInputStream()) );

            String line;

            while( (line = stdout.readLine()) != null )
            { 
                result.append( line+"\n");
            }            

            process.waitFor();

            log.debug("Done, returned = "+process.exitValue());
        }
        catch( Exception e )
        {
            log.error("RCS checkout failed",e);
        }
        
        return result.toString();
    }

    /**
     *  Puts the page into RCS and makes sure there is a fresh copy in
     *  the directory as well.
     */
    public void putPageText( String page, String text )
    {
        // Writes it in the dir.
        super.putPageText( page, text );

        log.debug( "Checking in text..." );

        try
        {
            String cmd = m_checkinCommand;
            String[] env = new String[0];

            cmd = TranslatorReader.replaceString( cmd, "%s", mangleName(page)+FILE_EXT );

            log.debug("Command = '"+cmd+"'");

            Process process = Runtime.getRuntime().exec( cmd, env, new File(getPageDirectory()) );

            process.waitFor();

            log.debug("Done, returned = "+process.exitValue());
        }
        catch( Exception e )
        {
            log.error("RCS checkin failed",e);
        }
    }

    // FIXME: Put the rcs date formats into properties as well.
    public Collection getVersionHistory( String page )
    {
        PatternMatcher matcher = new Perl5Matcher();
        PatternCompiler compiler = new Perl5Compiler();
        PatternMatcherInput input;

        log.debug("Getting RCS version history");

        ArrayList list = new ArrayList();        

        SimpleDateFormat rcsdatefmt = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

        try
        {
            Pattern revpattern  = compiler.compile("^revision \\d+\\.(\\d+)");
            Pattern datepattern = compiler.compile("^date:\\s*(.*);");

            String[] env = new String[0];

            String cmd = TranslatorReader.replaceString( m_fullLogCommand,
                                                         "%s",
                                                         mangleName(page)+FILE_EXT );
            
            Process process = Runtime.getRuntime().exec( cmd, env, new File(getPageDirectory()) );

            BufferedReader stdout = new BufferedReader( new InputStreamReader(process.getInputStream()) );

            String line;

            WikiPage info = null;

            while( (line = stdout.readLine()) != null )
            { 
                if( matcher.contains( line, revpattern ) )
                {
                    info = new WikiPage( page );

                    MatchResult result = matcher.getMatch();

                    int vernum = Integer.parseInt( result.group(1) );
                    info.setVersion( vernum );
                    list.add( info );
                }

                if( matcher.contains( line, datepattern ) )
                {
                    MatchResult result = matcher.getMatch();

                    Date d = rcsdatefmt.parse( result.group(1) );

                    info.setLastModified( d );
                }
            }

            process.waitFor();

        }
        catch( Exception e )
        {
            log.error( "RCS log failed", e );
        }

        return list;
    }
}