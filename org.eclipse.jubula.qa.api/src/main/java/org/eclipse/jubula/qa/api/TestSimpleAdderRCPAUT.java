/*******************************************************************************
 * Copyright (c) 2014 BREDEX GmbH.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     BREDEX GmbH - initial API and implementation and/or initial documentation
 *******************************************************************************/
package org.eclipse.jubula.qa.api;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import junit.framework.Assert;

import org.eclipse.jubula.client.AUT;
import org.eclipse.jubula.client.AUTAgent;
import org.eclipse.jubula.client.MakeR;
import org.eclipse.jubula.client.launch.AUTConfiguration;
import org.eclipse.jubula.toolkit.rcp.config.RCPAUTConfiguration;
import org.eclipse.jubula.tools.internal.registration.AutIdentifier;
import org.eclipse.jubula.tools.internal.xml.businessmodell.ComponentClass;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/** @author BREDEX GmbH */
public class TestSimpleAdderRCPAUT {
    /** AUT-Agent host name to use */
    public static final String AGENT_HOST = "g8.dev.bredex.local"; //$NON-NLS-1$
    /** AUT-Agent port to use */
    public static final int AGENT_PORT = 11022;
    /** the ERR_STILL_CONNECTED */
    private static final String ERR_STILL_CONNECTED = "Still connected to AUT-Agent via API"; //$NON-NLS-1$
    /** the ERR_NOT_CONNECTED */
    private static final String ERR_NOT_CONNECTED = "No connection to AUT-Agent via API"; //$NON-NLS-1$
    /** the AUT-Agent */
    private AUTAgent m_agent;
    /** the AUT */
    private AUT m_aut;

    /** prepare */
    @Before
    public void setUp() throws Exception {
        m_agent = MakeR.createAUTAgent(AGENT_HOST, AGENT_PORT);
        Assert.assertNotNull(m_agent);
        
        m_agent.connect();
        Assert.assertTrue(ERR_NOT_CONNECTED, m_agent.isConnected());
        
        final String autID = "SimpleAdder";  //$NON-NLS-1$
        AUTConfiguration config = new RCPAUTConfiguration(
            "api.aut.conf.simple.adder.rcp",  //$NON-NLS-1$
            new AutIdentifier(autID),
            "SimpleAdder.exe", //$NON-NLS-1$
            "k:\\guidancer\\Workspace\\hu_snapshot\\current\\platforms\\win32.win32.x86\\examples\\AUTs\\SimpleAdder\\rcp\\win32\\win32\\x86\\", //$NON-NLS-1$ 
            new String[]{
                "-clean" , //$NON-NLS-1$
                "-configuration", //$NON-NLS-1$
                "@none", //$NON-NLS-1$
                "-data", //$NON-NLS-1$
                "@none"}, //$NON-NLS-1$
            Locale.getDefault(), 
            Locale.getDefault());
        
        AutIdentifier id = m_agent.startAUT(config);
        
        // this e.g. might not be the case if AUT-Agent is running in
        // non-lenient mode and multiple AUTs with the same ID are supposed to
        // start
        Assert.assertEquals(id.getExecutableName(), autID);
        
        List<AutIdentifier> allRegisteredAUTIdentifier = m_agent
            .getAllRegisteredAUTIdentifier();
        
        Assert.assertTrue("Expected ID not found", //$NON-NLS-1$
            allRegisteredAUTIdentifier.contains(id)); 
        
        m_aut = m_agent.getAUT(id);
        
        Assert.assertTrue(!m_aut.isConnected());
        
        Map<ComponentClass, String> typeMapping = 
            new HashMap<ComponentClass, String>();
        typeMapping.put(
            new ComponentClass("org.eclipse.swt.widgets.Button"), //$NON-NLS-1$
                               "org.eclipse.jubula.rc.swt.tester.ButtonTester"); //$NON-NLS-1$ 
        
        m_aut.setTypeMapping(typeMapping);
        m_aut.connect();
    }

    /** the actual test method */
    @Test
    public void testAUT() throws Exception {
        Assert.assertTrue(m_aut.isConnected());
        
        
    }

    /** cleanup */
    @After
    public void tearDown() throws Exception {
        m_aut.disconnect();
        Assert.assertTrue(!m_aut.isConnected());
        
        m_agent.stopAUT(m_aut.getIdentifier());
        
        m_agent.disconnect();
        Assert.assertFalse(ERR_STILL_CONNECTED, m_agent.isConnected());
    }
}