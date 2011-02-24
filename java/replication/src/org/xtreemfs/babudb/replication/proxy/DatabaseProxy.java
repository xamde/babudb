/*
 * Copyright (c) 2011, Jan Stender, Bjoern Kolbeck, Mikael Hoegqvist,
 *                     Felix Hupfeld, Felix Langner, Zuse Institute Berlin
 * 
 * Licensed under the BSD License, see LICENSE file for details.
 * 
 */
package org.xtreemfs.babudb.replication.proxy;

import java.net.InetSocketAddress;

import org.xtreemfs.babudb.api.database.Database;
import org.xtreemfs.babudb.api.database.DatabaseInsertGroup;
import org.xtreemfs.babudb.api.database.DatabaseRequestListener;
import org.xtreemfs.babudb.api.database.DatabaseRequestResult;
import org.xtreemfs.babudb.api.database.ResultSet;
import org.xtreemfs.babudb.api.database.UserDefinedLookup;
import org.xtreemfs.babudb.api.exception.BabuDBException;
import org.xtreemfs.babudb.api.exception.BabuDBException.ErrorCode;
import org.xtreemfs.babudb.api.index.ByteRangeComparator;
import org.xtreemfs.babudb.log.LogEntry;
import org.xtreemfs.babudb.lsmdb.BabuDBInsertGroup;
import org.xtreemfs.babudb.lsmdb.DatabaseImpl;
import org.xtreemfs.babudb.pbrpc.GlobalTypes.EntryMap;
import org.xtreemfs.babudb.pbrpc.GlobalTypes.ErrorCodeResponse;
import org.xtreemfs.babudb.replication.policy.Policy;
import org.xtreemfs.babudb.replication.service.clients.ClientResponseFuture;
import org.xtreemfs.babudb.replication.service.clients.ClientResponseFuture.ClientResponseAvailableListener;
import org.xtreemfs.babudb.replication.transmission.PBRPCClientAdapter.ErrorCodeException;
import org.xtreemfs.foundation.buffer.ReusableBuffer;

import static org.xtreemfs.babudb.replication.transmission.ErrorCode.*;

/**
 * Stub to redirect Database requests to a remote master if necessary.
 * 
 * @see Policy
 * 
 * @author flangner
 * @since 01/19/2011
 */
class DatabaseProxy implements Database {

    private final DatabaseManagerProxy  dbMan;
    private final Policy                replicationPolicy;
    private final String                name;
    private final int                   id;
    private Database                    localDB;
    
    public DatabaseProxy(Database localDatabase, Policy replicationPolicy, 
            DatabaseManagerProxy dbManProxy) {
        
        assert (localDatabase != null);
        
        this.localDB = localDatabase;
        this.name = localDB.getName();
        this.id = ((DatabaseImpl) localDB).getLSMDB().getDatabaseId();
        this.replicationPolicy = replicationPolicy;
        this.dbMan = dbManProxy;
    }
    
    public DatabaseProxy(String dbName, int dbId, Policy replicationPolicy, 
            DatabaseManagerProxy dbManProxy) {
        
        this.name = dbName;
        this.id = dbId;
        this.localDB = null;
        this.replicationPolicy = replicationPolicy;
        this.dbMan = dbManProxy;
    }
    
    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.DatabaseRO#lookup(int, byte[], 
     *          java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<byte[]> lookup(int indexId, byte[] key, 
            final Object context) {
        
        InetSocketAddress master = null;
        try {
            master = getServerToPerformAt();
            
            if (master == null) {
                return localDB.lookup(indexId, key, context);
            }
        } catch (final BabuDBException e) {
            return new DatabaseRequestResult<byte[]>() {
                
                @Override
                public void registerListener(
                        DatabaseRequestListener<byte[]> listener) {
                    listener.failed(e, context);
                }
                
                @Override
                public byte[] get() throws BabuDBException {
                    throw e;
                }
            };
        } 
        
        final ClientResponseFuture<byte[], ErrorCodeResponse> r = dbMan.getClient().lookup(name, indexId, 
                ReusableBuffer.wrap(key), master);
        
        return new DatabaseRequestResult<byte[]>() {
            
            @Override
            public void registerListener(
                    final DatabaseRequestListener<byte[]> listener) {
                
                r.registerListener(new ClientResponseAvailableListener<byte[]>() {

                    @Override
                    public void responseAvailable(byte[] rp) {
                        listener.finished(rp, context);
                    }

                    @Override
                    public void requestFailed(Exception e) {
                        BabuDBException be = new BabuDBException(
                                ErrorCode.REPLICATION_FAILURE, e.getMessage());
                        
                        if (e instanceof ErrorCodeException) {
                            switch (((ErrorCodeException) e).getCode()) {
                            case DB_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_DB, e.getMessage());
                                    break;
                            case ENTRY_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_INDEX, e.getMessage());
                                    break;
                            }
                        }
                            
                        listener.failed(be, context);
                    }
                });
            }
            
            @Override
            public byte[] get() throws BabuDBException {
                try {
                    return r.get();
                } catch (ErrorCodeException ece) {
                    switch (ece.getCode()) {
                    case DB_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_DB, 
                                                  ece.getMessage());
                        
                    case ENTRY_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_INDEX, 
                                                  ece.getMessage());
                        
                    default : 
                        throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                                ece.getMessage());
                    }
                } catch (Exception e) {
                    throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                            e.getMessage());
                }
            }
        };
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.DatabaseRO#prefixLookup(int, 
     *          byte[], java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<ResultSet<byte[], byte[]>> prefixLookup(
            int indexId, byte[] key, final Object context) {
        
        InetSocketAddress master = null;
        try {
            master = getServerToPerformAt();
            
            if (master == null) {
                return localDB.prefixLookup(indexId, key, context);
            }
        } catch (final BabuDBException e) {
            return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
                
                @Override
                public void registerListener(
                        DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                    listener.failed(e, context);
                }
                
                @Override
                public ResultSet<byte[], byte[]> get() 
                        throws BabuDBException {
                    throw e;
                }
            };
        }
        
        final ClientResponseFuture<ResultSet<byte[], byte[]>, EntryMap> r = 
            dbMan.getClient().prefixLookup(name, indexId, ReusableBuffer.wrap(key), master);
        
        return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
            
            @Override
            public void registerListener(
                    final DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                
                r.registerListener(new ClientResponseAvailableListener<ResultSet<byte[], byte[]>>() {

                    @Override
                    public void responseAvailable(ResultSet<byte[], byte[]> rp) {
                        listener.finished(rp, context);
                    }

                    @Override
                    public void requestFailed(Exception e) {
                        BabuDBException be = new BabuDBException(
                                ErrorCode.REPLICATION_FAILURE, e.getMessage());
                        
                        if (e instanceof ErrorCodeException) {
                            switch (((ErrorCodeException) e).getCode()) {
                            case DB_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_DB, e.getMessage());
                                    break;
                            case ENTRY_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_INDEX, e.getMessage());
                                    break;
                            }
                        }
                            
                        listener.failed(be, context);
                    }
                });
            }
            
            @Override
            public ResultSet<byte[], byte[]> get() throws BabuDBException {
                try {
                    return r.get();
                } catch (ErrorCodeException ece) {
                    switch (ece.getCode()) {
                    case DB_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_DB, 
                                                  ece.getMessage());
                        
                    case ENTRY_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_INDEX, 
                                                  ece.getMessage());
                        
                    default : 
                        throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                                ece.getMessage());
                    }
                } catch (Exception e) {
                    throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                            e.getMessage());
                }
            }
        };
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.DatabaseRO#reversePrefixLookup(int, 
     *          byte[], java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<ResultSet<byte[], byte[]>> 
            reversePrefixLookup(int indexId, byte[] key, final Object context) {
        
        InetSocketAddress master = null;
        try {
            master = getServerToPerformAt();
            if (master == null) {
                return localDB.reversePrefixLookup(indexId, key, context);
            }
        } catch (final BabuDBException e) {
            return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
                
                @Override
                public void registerListener(
                        DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                    listener.failed(e, context);
                }
                
                @Override
                public ResultSet<byte[], byte[]> get() 
                        throws BabuDBException {
                    throw e;
                }
            };
        }
        
        final ClientResponseFuture<ResultSet<byte[], byte[]>, EntryMap> r = 
            dbMan.getClient().prefixLookupR(name, indexId, ReusableBuffer.wrap(key), master);
        
        return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
            
            @Override
            public void registerListener(
                    final DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                
                r.registerListener(new ClientResponseAvailableListener<ResultSet<byte[], byte[]>>() {

                    @Override
                    public void responseAvailable(ResultSet<byte[], byte[]> rp) {
                        listener.finished(rp, context);
                    }

                    @Override
                    public void requestFailed(Exception e) {
                        BabuDBException be = new BabuDBException(
                                ErrorCode.REPLICATION_FAILURE, e.getMessage());
                        
                        if (e instanceof ErrorCodeException) {
                            switch (((ErrorCodeException) e).getCode()) {
                            case DB_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_DB, e.getMessage());
                                    break;
                            case ENTRY_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_INDEX, e.getMessage());
                                    break;
                            }
                        }
                            
                        listener.failed(be, context);
                    }
                });
            }
            
            @Override
            public ResultSet<byte[], byte[]> get() throws BabuDBException {
                try {
                    return r.get();
                } catch (ErrorCodeException ece) {
                    switch (ece.getCode()) {
                    case DB_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_DB, 
                                                  ece.getMessage());
                        
                    case ENTRY_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_INDEX, 
                                                  ece.getMessage());
                        
                    default : 
                        throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                                ece.getMessage());
                    }
                } catch (Exception e) {
                    throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                            e.getMessage());
                }
            }
        };
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.DatabaseRO#rangeLookup(int, byte[], 
     *          byte[], java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<ResultSet<byte[], byte[]>> rangeLookup(
            int indexId, byte[] from, byte[] to, final Object context) {
        
        InetSocketAddress master = null;
        try {
            master = getServerToPerformAt();
            if (master == null) {
                return localDB.rangeLookup(indexId, from, to, context);
            }
        } catch (final BabuDBException e) {
            return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
                
                @Override
                public void registerListener(
                        DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                    listener.failed(e, context);
                }
                
                @Override
                public ResultSet<byte[], byte[]> get() 
                        throws BabuDBException {
                    throw e;
                }
            };
        }
        
        final ClientResponseFuture<ResultSet<byte[], byte[]>, EntryMap> r = 
            dbMan.getClient().rangeLookup(name, indexId, ReusableBuffer.wrap(from), 
                    ReusableBuffer.wrap(to), master);
        
        return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
            
            @Override
            public void registerListener(
                    final DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                
                r.registerListener(new ClientResponseAvailableListener<ResultSet<byte[], byte[]>>() {

                    @Override
                    public void responseAvailable(ResultSet<byte[], byte[]> rp) {
                        listener.finished(rp, context);
                    }

                    @Override
                    public void requestFailed(Exception e) {
                        BabuDBException be = new BabuDBException(
                                ErrorCode.REPLICATION_FAILURE, e.getMessage());
                        
                        if (e instanceof ErrorCodeException) {
                            switch (((ErrorCodeException) e).getCode()) {
                            case DB_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_DB, e.getMessage());
                                    break;
                            case ENTRY_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_INDEX, e.getMessage());
                                    break;
                            }
                        }
                            
                        listener.failed(be, context);
                    }
                });
            }
            
            @Override
            public ResultSet<byte[], byte[]> get() throws BabuDBException {
                try {
                    return r.get();
                } catch (ErrorCodeException ece) {
                    switch (ece.getCode()) {
                    case DB_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_DB, 
                                                  ece.getMessage());
                        
                    case ENTRY_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_INDEX, 
                                                  ece.getMessage());
                        
                    default : 
                        throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                                ece.getMessage());
                    }
                } catch (Exception e) {
                    throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                            e.getMessage());
                }
            }
        };
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.DatabaseRO#reverseRangeLookup(int, 
     *          byte[], byte[], java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<ResultSet<byte[], byte[]>> 
            reverseRangeLookup(int indexId, byte[] from, byte[] to, 
            final Object context) {
        
        InetSocketAddress master = null;
        try {
            master = getServerToPerformAt();
            if (master == null) {
                return localDB.reverseRangeLookup(indexId, from, to, context);
            }
        } catch (final BabuDBException e) {
            return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
                
                @Override
                public void registerListener(
                        DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                    listener.failed(e, context);
                }
                
                @Override
                public ResultSet<byte[], byte[]> get() 
                        throws BabuDBException {
                    throw e;
                }
            };
        }
        
        final ClientResponseFuture<ResultSet<byte[], byte[]>, EntryMap> r = 
            dbMan.getClient().rangeLookupR(name, indexId, 
                    ReusableBuffer.wrap(from), ReusableBuffer.wrap(to), master);
        
        return new DatabaseRequestResult<ResultSet<byte[], byte[]>>() {
            
            @Override
            public void registerListener(
                    final DatabaseRequestListener<ResultSet<byte[], byte[]>> listener) {
                
                r.registerListener(new ClientResponseAvailableListener<ResultSet<byte[], byte[]>>() {

                    @Override
                    public void responseAvailable(ResultSet<byte[], byte[]> rp) {
                        listener.finished(rp, context);
                    }

                    @Override
                    public void requestFailed(Exception e) {
                        BabuDBException be = new BabuDBException(
                                ErrorCode.REPLICATION_FAILURE, e.getMessage());
                        
                        if (e instanceof ErrorCodeException) {
                            switch (((ErrorCodeException) e).getCode()) {
                            case DB_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_DB, e.getMessage());
                                    break;
                            case ENTRY_UNAVAILABLE : be = new BabuDBException(
                                    ErrorCode.NO_SUCH_INDEX, e.getMessage());
                                    break;
                            }
                        }
                            
                        listener.failed(be, context);
                    }
                });
            }
            
            @Override
            public ResultSet<byte[], byte[]> get() throws BabuDBException {
                try {
                    return r.get();
                } catch (ErrorCodeException ece) {
                    switch (ece.getCode()) {
                    case DB_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_DB, 
                                                  ece.getMessage());
                        
                    case ENTRY_UNAVAILABLE : 
                        throw new BabuDBException(ErrorCode.NO_SUCH_INDEX, 
                                                  ece.getMessage());
                        
                    default : 
                        throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                                ece.getMessage());
                    }
                } catch (Exception e) {
                    throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                            e.getMessage());
                }
            }
        };
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.DatabaseRO#userDefinedLookup(
     *          org.xtreemfs.babudb.api.database.UserDefinedLookup, 
     *          java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<Object> userDefinedLookup(
            UserDefinedLookup udl, final Object context) {
        
        InetSocketAddress master = null;
        try {
            
            master = getServerToPerformAt();
            if (master == null) {
                return localDB.userDefinedLookup(udl, context);
            }
        } catch (final BabuDBException e) {
            return new DatabaseRequestResult<Object>() {
                
                @Override
                public void registerListener(
                        DatabaseRequestListener<Object> listener) {
                    listener.failed(e, context);
                }
                
                @Override
                public Object get() 
                        throws BabuDBException {
                    throw e;
                }
            };
        }
        
        // TODO RPC: userLookup return(Object ... will be hard to serialize)
        throw new UnsupportedOperationException("This operation is " +
        		"not supported by the replication-plugin yet.");
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.DatabaseRO#shutdown()
     */
    @Override
    public void shutdown() throws BabuDBException {
        
        if (localDB != null) {
            localDB.shutdown();
        }
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.Database#getName()
     */
    @Override
    public String getName() {
        return name;
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.Database#createInsertGroup()
     */
    @Override
    public DatabaseInsertGroup createInsertGroup() {
        return BabuDBInsertGroup.createInsertGroup(id);
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.Database#getComparators()
     */
    @Override
    public ByteRangeComparator[] getComparators() {
        
        InetSocketAddress master = null;
        try {
            
            master = getServerToPerformAt();
            if (master == null) {
                return localDB.getComparators();
            }
        } catch (BabuDBException e) { /* ignored */ }
        
        // TODO RPC: ByteRangeComparators have to be serializable 
        throw new UnsupportedOperationException("This operation is " +
                "not supported by the replication-plugin yet.");
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.Database#singleInsert(int, byte[], 
     *          byte[], java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<Object> singleInsert(int indexId, byte[] key, 
            byte[] value, Object context) {
        
        DatabaseInsertGroup irg = createInsertGroup();
        irg.addInsert(indexId, key, value);
        return insert(irg, context);
    }

    /* (non-Javadoc)
     * @see org.xtreemfs.babudb.api.database.Database#insert(
     *          org.xtreemfs.babudb.api.database.DatabaseInsertGroup, 
     *          java.lang.Object)
     */
    @Override
    public DatabaseRequestResult<Object> insert(DatabaseInsertGroup irg, final Object context) {
        
        try {
            
            return dbMan.getPersistenceManager().makePersistent(LogEntry.PAYLOAD_TYPE_INSERT, 
                                                                new Object[] { irg, null, null });
        } catch (final BabuDBException e) {
            
            return new DatabaseRequestResult<Object>() {
                
                @Override
                public void registerListener(DatabaseRequestListener<Object> listener) {
                    listener.failed(e, context);
                }
                
                @Override
                public Object get() throws BabuDBException {
                    throw e;
                }
            };
        }
    }

    /**
     * @param type - of the request.
     * 
     * @return the host to perform the request at, or null, if it is permitted to perform the 
     *         request locally.
     * @throws BabuDBException if replication is currently not available.
     */
    private InetSocketAddress getServerToPerformAt() throws BabuDBException {
        
        InetSocketAddress master = dbMan.getReplicationManager().getMaster();
        
        if (master == null) {
            throw new BabuDBException(ErrorCode.REPLICATION_FAILURE, 
                    "A majority of servers is currently not available.");
        }
        
        boolean isMaster = dbMan.getReplicationManager().isItMe(master);
        
        if (isMaster || !replicationPolicy.lookUpIsMasterRestricted()) {
            
            if (localDB == null && 
                (isMaster || !replicationPolicy.dbModificationIsMasterRestricted())) {
                localDB = dbMan.getLocalDatabase(name);
            } else if (localDB == null) {
                return master;
            }
             
            return null;
        }

        return master;
    }
}
