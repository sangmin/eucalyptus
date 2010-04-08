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
 *******************************************************************************
 * Author: chris grzegorczyk <grze@eucalyptus.com>
 */
package com.eucalyptus.auth

import java.io.Serializable;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.util.List;
import java.util.ArrayList;

import org.bouncycastle.util.encoders.UrlBase64;
import javax.persistence.Entity;
import javax.persistence.Id;
import org.hibernate.annotations.Cache
import org.hibernate.annotations.CacheConcurrencyStrategy
import org.hibernate.annotations.GenericGenerator

import javax.persistence.MappedSuperclass;
import javax.persistence.Table
import javax.persistence.GeneratedValue
import javax.persistence.Column
import javax.persistence.Lob
import javax.persistence.OneToMany
import javax.persistence.OneToOne
import javax.persistence.FetchType
import javax.persistence.CascadeType
import javax.persistence.JoinTable
import javax.persistence.JoinColumn
import javax.persistence.Version;
import javax.persistence.Temporal;
import javax.persistence.TemporalType;
import javax.persistence.Version;
import javax.persistence.PersistenceContext;

import org.hibernate.sql.Alias

import com.google.common.collect.Lists;
import com.eucalyptus.auth.principal.User;
import com.eucalyptus.auth.util.B64;
import com.eucalyptus.auth.util.PEMFiles;
import com.eucalyptus.entities.AbstractPersistent;

@Entity
@PersistenceContext(name="eucalyptus_auth")
@Table( name = "auth_users" )
@Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
public class UserEntity extends AbstractPersistent implements Serializable, User {
  @Column( name = "auth_user_name", unique=true )
  String name;
  @Column( name = "auth_user_query_id" )
  String queryId;
  @Column( name = "auth_user_secretkey" )
  String secretKey;
  @Column( name = "auth_user_password" )
  String password;
  @Column( name = "auth_user_is_admin" )
  Boolean administrator;
  @Column( name = "auth_user_is_enabled" )
  Boolean enabled;
  @Column( name = "auth_user_token" )
  String  token;
  @Column( name = "auth_user_certificate" )
  String certificate;  
  @OneToMany( cascade=[CascadeType.ALL], fetch=FetchType.EAGER )
  @JoinTable(name = "auth_user_has_x509", joinColumns = [ @JoinColumn( name = "auth_user_id" ) ],inverseJoinColumns = [ @JoinColumn( name = "auth_x509_id" ) ])
  @Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
  List<X509Cert> oldCertificates = []
  
  public UserEntity(){
  }
  
  public UserEntity( String userName ){
    this.name = userName
  }
  
  public UserEntity( String userName, Boolean enabled ){
    this(userName);
    this.setEnabled( enabled );
  }

  public void revokeX509Certificate() {
    if( this.getCertificate() != null ) {
      X509Certificate c = this.getX509Certificate( );
      this.getOldCertificates( ).add( c );
      this.setCertificate( null );
    }
  }
  
  public void revokeSecretKey() {
    this.setSecretKey( null );
  }
  
  public List<X509Certificate> getAllX509Certificates() {
    List<X509Certificate> certs = Lists.newArrayList( this.getX509Certificate() );
    for( X509Cert c : this.getCertificates() ) {
      certs.add( X509Cert.toCertificate( c ) );
    }
    return certs;
  }
  
  public X509Certificate getX509Certificate() {
    return this.getCertificate()!=null?PEMFiles.getCert( B64.url.dec( this.getCertificate( ) ) ):null;
  }
  
  public void setX509Certificate( X509Certificate x509 ) {
    this.revokeX509Certificate();
    this.setCertificate( B64.url.encString( PEMFiles.getBytes( x509 ) ) );
  }
  public Boolean isEnabled() {
    return enabled;
  }
  public Boolean isAdministrator() {
    return administrator;
  }
  public BigInteger getNumber() {
    return new BigInteger( this.getId(), 16 );
  }
  @Override
  public int hashCode( ) {
    final int prime = 31;
    int result = super.hashCode( );
    result = prime * result + ( ( name == null ) ? 0 : name.hashCode( ) );
    return result;
  }
  @Override
  public boolean equals( Object obj ) {
    if ( this.is( obj ) ) return true;
    if ( !super.equals( obj ) ) return false;
    if ( getClass( ).is( obj.getClass( ) ) ) return false;
    User other = ( User ) obj;
    if ( name == null ) {
      if ( other.name != null ) return false;
    } else if ( !name.equals( other.name ) ) return false;
    return true;
  }  
}

@Entity
@PersistenceContext(name="eucalyptus_auth")
@Table(name="auth_x509")
@Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
public class X509Cert extends AbstractPersistent implements Serializable {
  @Column( name = "auth_x509_alias", unique=true )
  String alias
  @Lob
  @Column( name = "auth_x509_pem_certificate" )
  String pemCertificate
  public X509Cert(){
  }
  public X509Cert( String alias ) {
    this.alias = alias
  }
  public static X509Cert fromCertificate(X509Certificate x509) {
    X509Cert x = new X509Cert( );
    x.setAlias(x509.getSerialNumber( ).toString());
    x.setPemCertificate( B64.url.encString( PEMFiles.getBytes( x509 ) ) );
    return x;
  }  
  public static X509Certificate toCertificate(X509Cert x509) {
    return PEMFiles.getCert( B64.url.dec( x509.getPemCertificate( ) ) );
  }  
  
  @Override
  public int hashCode( ) {
    final int prime = 31;
    int result = super.hashCode( );
    result = prime * result + ( ( alias == null ) ? 0 : alias.hashCode( ) );
    return result;
  }
  @Override
  public boolean equals( Object obj ) {
    if ( this.is( obj ) ) return true;
    if ( !super.equals( obj ) ) return false;
    if ( !getClass( ).equals( obj.getClass( ) ) ) return false;
    X509Cert other = ( X509Cert ) obj;
    if ( alias == null ) {
      if ( other.alias != null ) return false;
    } else if ( !alias.equals( other.alias ) ) return false;
    return true;
  }
}

@Entity
@PersistenceContext(name="eucalyptus_auth")
@Table( name = "auth_clusters" )
@Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
public class ClusterCredentials extends AbstractPersistent implements Serializable {
  @Column( name = "auth_cluster_name", unique=true )
  String clusterName
  @OneToOne(cascade = CascadeType.ALL)
  @JoinColumn(name="auth_cluster_x509_certificate")
  @Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
  X509Cert clusterCertificate
  @OneToOne(cascade = CascadeType.ALL)
  @JoinColumn(name="auth_cluster_node_x509_certificate")
  @Cache( usage = CacheConcurrencyStrategy.READ_WRITE )
  X509Cert nodeCertificate  
  public ClusterCredentials( ) {
  }
  public ClusterCredentials( String clusterName ) {
    this.clusterName = clusterName;
  }
  
  @Override
  public int hashCode( ) {
    final int prime = 31;
    int result = super.hashCode( );
    result = prime * result + ( ( clusterName == null ) ? 0 : clusterName.hashCode( ) );
    return result;
  }
  @Override
  public boolean equals( Object obj ) {
    if ( this.is( obj ) ) return true;
    if ( !super.equals( obj ) ) return false;
    if ( !getClass( ).equals( obj.getClass( ) ) ) return false;
    ClusterCredentials other = ( ClusterCredentials ) obj;
    if ( clusterName == null ) {
      if ( other.clusterName != null ) return false;
    } else if ( !clusterName.equals( other.clusterName ) ) return false;
    return true;
  }  
}

