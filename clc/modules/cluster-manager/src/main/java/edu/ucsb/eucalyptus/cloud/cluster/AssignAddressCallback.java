/*******************************************************************************
 *Copyright (c) 2009  Eucalyptus Systems, Inc.
 * 
 *  This program is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, only version 3 of the License.
 * 
 * 
 *  This file is distributed in the hope that it will be useful, but WITHOUT
 *  ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 *  FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 *  for more details.
 * 
 *  You should have received a copy of the GNU General Public License along
 *  with this program.  If not, see <http://www.gnu.org/licenses/>.
 * 
 *  Please contact Eucalyptus Systems, Inc., 130 Castilian
 *  Dr., Goleta, CA 93101 USA or visit <http://www.eucalyptus.com/licenses/>
 *  if you need additional information or have any questions.
 * 
 *  This file may incorporate work covered under the following copyright and
 *  permission notice:
 * 
 *    Software License Agreement (BSD License)
 * 
 *    Copyright (c) 2008, Regents of the University of California
 *    All rights reserved.
 * 
 *    Redistribution and use of this software in source and binary forms, with
 *    or without modification, are permitted provided that the following
 *    conditions are met:
 * 
 *      Redistributions of source code must retain the above copyright notice,
 *      this list of conditions and the following disclaimer.
 * 
 *      Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 * 
 *    THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS
 *    IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 *    TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A
 *    PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER
 *    OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 *    EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 *    PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 *    PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 *    LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 *    NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 *    SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE. USERS OF
 *    THIS SOFTWARE ACKNOWLEDGE THE POSSIBLE PRESENCE OF OTHER OPEN SOURCE
 *    LICENSED MATERIAL, COPYRIGHTED MATERIAL OR PATENTED MATERIAL IN THIS
 *    SOFTWARE, AND IF ANY SUCH MATERIAL IS DISCOVERED THE PARTY DISCOVERING
 *    IT MAY INFORM DR. RICH WOLSKI AT THE UNIVERSITY OF CALIFORNIA, SANTA
 *    BARBARA WHO WILL THEN ASCERTAIN THE MOST APPROPRIATE REMEDY, WHICH IN
 *    THE REGENTS’ DISCRETION MAY INCLUDE, WITHOUT LIMITATION, REPLACEMENT
 *    OF THE CODE SO IDENTIFIED, LICENSING OF THE CODE SO IDENTIFIED, OR
 *    WITHDRAWAL OF THE CODE CAPABILITY TO THE EXTENT NEEDED TO COMPLY WITH
 *    ANY SUCH LICENSES OR RIGHTS.
 *******************************************************************************/
/*
 * Author: chris grzegorczyk <grze@eucalyptus.com>
 */
package edu.ucsb.eucalyptus.cloud.cluster;

import org.apache.log4j.Logger;
import com.eucalyptus.address.Address;
import com.eucalyptus.address.Addresses;
import com.eucalyptus.address.Address.Transition;
import com.eucalyptus.util.LogUtil;
import edu.ucsb.eucalyptus.constants.VmState;
import edu.ucsb.eucalyptus.msgs.AssignAddressType;
import edu.ucsb.eucalyptus.msgs.EucalyptusMessage;
import edu.ucsb.eucalyptus.msgs.EventRecord;

public class AssignAddressCallback extends QueuedEventCallback<AssignAddressType> {
  private static Logger LOG = Logger.getLogger( AssignAddressCallback.class );
  
  private Address       address;
  
  public AssignAddressCallback( Address address ) {
    this.address = address;
    super.setRequest( new AssignAddressType( address.getName( ), address.getInstanceAddress( ), address.getInstanceId( ) ) );
  }
  
  @Override
  public void prepare( AssignAddressType msg ) throws Exception {
    try {
      VmInstance vm = VmInstances.getInstance( ).lookup( msg.getInstanceId( ) );
      VmState vmState = vm.getState( );
      if ( !VmState.RUNNING.equals( vmState ) && !VmState.PENDING.equals( vmState ) ) {
        LOG.debug( EventRecord.here( AssignAddressCallback.class, Transition.assigning, LogUtil.FAIL, address.toString( ) ) );
        this.address.clearPending( );
        this.address.release( );
        throw new IllegalStateException( "Ignoring assignment to a vm which is not running: " + msg );
      }
    } catch ( Exception e ) {
      LOG.debug( e, e );
      this.address.clearPending( );
      throw e;
    }
    LOG.debug( EventRecord.here( AssignAddressCallback.class, Transition.assigning, address.toString( ) ) );
  }
  
  @Override
  public void verify( EucalyptusMessage msg ) throws Exception {
    this.address.clearPending( );
    LOG.info( EventRecord.here( AssignAddressCallback.class, Address.State.assigned, LogUtil.dumpObject( address ) ) );
    try {
      VmInstance vm = VmInstances.getInstance( ).lookup( this.address.getInstanceId( ) );
      vm.getNetworkConfig( ).setIgnoredPublicIp( this.address.getName( ) );
    } catch ( Exception e ) {
      Addresses.unassign( address );
      LOG.debug( e, e );
    }
  }
  
  @Override
  public void fail( Throwable e ) {
    LOG.info( EventRecord.here( AssignAddressCallback.class, Address.State.assigned, LogUtil.FAIL, LogUtil.dumpObject( address ) ) );
    LOG.debug( LogUtil.subheader( this.getRequest( ).toString( ) ) );
    LOG.debug( e, e );
    this.address.clearPending( );
    this.address.unassign( );
  }
  
}
