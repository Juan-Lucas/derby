/*

   Derby - Class org.apache.derby.impl.sql.compile.MergeNode

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

package	org.apache.derby.impl.sql.compile;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.reference.ClassName;
import org.apache.derby.iapi.reference.SQLState;
import org.apache.derby.iapi.services.classfile.VMOpcode;
import org.apache.derby.iapi.services.compiler.MethodBuilder;
import org.apache.derby.iapi.services.context.ContextManager;
import org.apache.derby.iapi.sql.compile.CompilerContext;
import org.apache.derby.iapi.sql.compile.IgnoreFilter;
import org.apache.derby.iapi.sql.compile.Visitor;
import org.apache.derby.iapi.sql.conn.Authorizer;
import org.apache.derby.iapi.sql.dictionary.ColumnDescriptor;
import org.apache.derby.iapi.sql.dictionary.ColumnDescriptorList;
import org.apache.derby.iapi.sql.dictionary.DataDictionary;
import org.apache.derby.iapi.sql.dictionary.TableDescriptor;
import org.apache.derby.iapi.sql.execute.ConstantAction;
import org.apache.derby.iapi.util.IdUtil;
import org.apache.derby.shared.common.sanity.SanityManager;

/**
 * <p>
 * A MergeNode represents a MERGE statement. The statement looks like
 * this...
 * </p>
 *
 * <pre>
 * MERGE INTO targetTable
 * USING sourceTable
 * ON searchCondition
 * matchingClause1 ... matchingClauseN
 * </pre>
 *
 * <p>
 * ...where each matching clause looks like this...
 * </p>
 *
 * <pre>
 * WHEN MATCHED [ AND matchingRefinement ] THEN DELETE
 * </pre>
 *
 * <p>
 * ...or
 * </p>
 *
 * <pre>
 * WHEN MATCHED [ AND matchingRefinement ] THEN UPDATE SET col1 = expr1, ... colM = exprM
 * </pre>
 *
 * <p>
 * ...or
 * </p>
 *
 * <pre>
 * WHEN NOT MATCHED [ AND matchingRefinement ] THEN INSERT columnList VALUES valueList
 * </pre>
 *
 * <p>
 * The Derby compiler essentially rewrites this statement into a driving left join
 * followed by a series of DELETE/UPDATE/INSERT actions. The left join looks like
 * this:
 * </p>
 *
 * <pre>
 * SELECT selectList FROM sourceTable LEFT OUTER JOIN targetTable ON searchCondition
 * </pre>
 *
 * <p>
 * The selectList of the driving left join consists of the following:
 * </p>
 *
 * <ul>
 * <li>All of the columns mentioned in the searchCondition.</li>
 * <li>All of the columns mentioned in the matchingRefinement clauses.</li>
 * <li>All of the columns mentioned in the SET clauses and the INSERT columnLists and valueLists.</li>
 * <li>All additional columns needed for the triggers and foreign keys fired by the DeleteResultSets
 * and UpdateResultSets constructed for the WHEN MATCHED clauses.</li>
 * <li>All additional columns needed to build index rows and evaluate generated columns
 * needed by the UpdateResultSets constructed for the WHEN MATCHED...THEN UPDATE clauses.</li>
 * <li>A trailing targetTable.RowLocation column.</li>
 * </ul>
 *
 * <p>
 * The matchingRefinement expressions are bound and generated against the
 * FromList of the driving left join. Dummy DeleteNode, UpdateNode, and InsertNode
 * statements are independently constructed in order to bind and generate the DELETE/UPDATE/INSERT
 * actions.
 * </p>
 *
 * <p>
 * At execution time, the targetTable.RowLocation column is used to determine
 * whether a given driving row matches. The row matches iff targetTable.RowLocation is not null.
 * The driving row is then assigned to the
 * first DELETE/UPDATE/INSERT action to which it applies. The relevant columns from
 * the driving row are extracted and buffered in a temporary table specific to that
 * DELETE/UPDATE/INSERT action. After the driving left join has been processed,
 * the DELETE/UPDATE/INSERT actions are run in order, each taking its corresponding
 * temporary table as its source ResultSet.
 * </p>
 */

public final class MergeNode extends DMLModStatementNode
{
    ///////////////////////////////////////////////////////////////////////////////////
    //
    // CONSTANTS
    //
    ///////////////////////////////////////////////////////////////////////////////////

    public  static  final   int SOURCE_TABLE_INDEX = 0;
    public  static  final   int TARGET_TABLE_INDEX = 1;

	private static  final   String  TARGET_ROW_LOCATION_NAME = "###TargetRowLocation";

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // STATE
    //
    ///////////////////////////////////////////////////////////////////////////////////

    // constructor args
    private FromBaseTable   _targetTable;
    private FromTable   _sourceTable;
    private ValueNode   _searchCondition;
    private ArrayList<MatchingClauseNode>   _matchingClauses;

    // filled in at bind() time
    private ResultColumnList    _selectList;
    private FromList                _leftJoinFromList;
    private HalfOuterJoinNode   _hojn;

    // filled in at generate() time
    private ConstantAction      _constantAction;
    private CursorNode          _leftJoinCursor;

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // CONSTRUCTOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

    /**
     * <p>
     * Constructor for a MergeNode.
     * </p>
     */
    public  MergeNode
        (
         FromTable          targetTable,
         FromTable          sourceTable,
         ValueNode          searchCondition,
         ArrayList<MatchingClauseNode>  matchingClauses,
         ContextManager     cm
         )
        throws StandardException
    {
        super( null, null, cm );

        if ( !( targetTable instanceof FromBaseTable) ) { notBaseTable(); }
        else { _targetTable = (FromBaseTable) targetTable; }
        
        _sourceTable = sourceTable;
        _searchCondition = searchCondition;
        _matchingClauses = matchingClauses;
    }

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // bind() BEHAVIOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

    @Override
	public void bindStatement() throws StandardException
	{
        DataDictionary  dd = getDataDictionary();

        // source table must be a vti or base table
        if (
            !(_sourceTable instanceof FromVTI) &&
            !(_sourceTable instanceof FromBaseTable)
            )
        {
            throw StandardException.newException( SQLState.LANG_SOURCE_NOT_BASE_VIEW_OR_VTI );
        }

        // source and target may not have the same correlation names
        if ( getExposedName( _targetTable ).equals( getExposedName( _sourceTable ) ) )
        {
            throw StandardException.newException( SQLState.LANG_SAME_EXPOSED_NAME );
        }

        // don't allow derived column lists right now
        forbidDerivedColumnLists();
        
        // synonyms not allowed
        forbidSynonyms( dd );

        //
        // Don't add any privileges until we bind the matching clauses.
        //
        IgnoreFilter    ignorePermissions = new IgnoreFilter();
        getCompilerContext().addPrivilegeFilter( ignorePermissions );
            
        FromList    dfl = new FromList( getContextManager() );
        FromTable   dflSource = cloneFromTable( _sourceTable );
        FromBaseTable   dflTarget = (FromBaseTable) cloneFromTable( _targetTable );
        dfl.addFromTable( dflSource );
        dfl.addFromTable( dflTarget );
        dfl.bindTables( dd, new FromList( getOptimizerFactory().doJoinOrderOptimization(), getContextManager() ) );

        // target table must be a base table
        if ( !targetIsBaseTable( dflTarget ) ) { notBaseTable(); }

        // ready to add permissions
        getCompilerContext().removePrivilegeFilter( ignorePermissions );

        for ( MatchingClauseNode mcn : _matchingClauses )
        {
            FromList    dummyFromList = cloneFromList( dd, dflTarget );
            FromBaseTable   dummyTargetTable = (FromBaseTable) dummyFromList.elementAt( TARGET_TABLE_INDEX );
            mcn.bind( dd, this, dummyFromList, dummyTargetTable );
        }
        
        bindLeftJoin( dd );
	}

    /** Create a FromList for binding a WHEN [ NOT ] MATCHED clause */
    private FromList    cloneFromList( DataDictionary dd, FromBaseTable targetTable )
        throws StandardException
    {
        FromList    dummyFromList = new FromList( getContextManager() );
        FromBaseTable   dummyTargetTable = new FromBaseTable
            (
             targetTable.getTableNameField(),
             targetTable.correlationName,
             null,
             null,
             getContextManager()
             );
        FromTable       dummySourceTable = cloneFromTable( _sourceTable );

        dummyTargetTable.setMergeTableID( ColumnReference.MERGE_TARGET );
        dummySourceTable.setMergeTableID ( ColumnReference.MERGE_SOURCE );
        
        dummyFromList.addFromTable( dummySourceTable );
        dummyFromList.addFromTable( dummyTargetTable );
        
        //
        // Don't add any privileges while binding the tables.
        //
        IgnoreFilter    ignorePermissions = new IgnoreFilter();
        getCompilerContext().addPrivilegeFilter( ignorePermissions );
             
        dummyFromList.bindTables( dd, new FromList( getOptimizerFactory().doJoinOrderOptimization(), getContextManager() ) );

        // ready to add permissions
        getCompilerContext().removePrivilegeFilter( ignorePermissions );
        
        return dummyFromList;
    }

    /** Get the exposed name of a FromTable */
    private String  getExposedName( FromTable ft ) throws StandardException
    {
        return ft.getTableName().getTableName();
    }

    /**
     *<p>
     * Because of name resolution complexities, we do not allow derived column lists
     * on source or target tables. These lists arise in queries like the following:
     * </p>
     *
     * <pre>
     * merge into t1 r( x )
     * using t2 on r.x = t2.a
     * when matched then delete;
     * 
     * merge into t1
     * using t2 r( x ) on t1.a = r.x
     * when matched then delete;
     * </pre>
     */
    private void    forbidDerivedColumnLists() throws StandardException
    {
        if ( (_sourceTable.getResultColumns() != null) || (_targetTable.getResultColumns() != null) )
        {
            throw StandardException.newException( SQLState.LANG_NO_DCL_IN_MERGE );
        }
    }

    /** Neither the source nor the target table may be a synonym */
    private void    forbidSynonyms( DataDictionary dd )    throws StandardException
    {
        forbidSynonyms( dd, _targetTable.getTableNameField().cloneMe() );
        if ( _sourceTable instanceof FromBaseTable )
        {
            forbidSynonyms( dd, ((FromBaseTable)_sourceTable).getTableNameField().cloneMe() );
        }
    }
    private void    forbidSynonyms( DataDictionary dd, TableName tableName ) throws StandardException
    {
        tableName.bind( dd );

        TableName   synonym = resolveTableToSynonym( tableName );
        if ( synonym != null )
        {
            throw StandardException.newException( SQLState.LANG_NO_SYNONYMS_IN_MERGE );
        }
    }

    /**
     * Bind the driving left join select.
     * Stuffs the left join SelectNode into the resultSet variable.
     */
    private void    bindLeftJoin( DataDictionary dd )   throws StandardException
    {
        CompilerContext cc = getCompilerContext();
        final int previousReliability = cc.getReliability();
        
        try {
            cc.setReliability( previousReliability | CompilerContext.SQL_IN_ROUTINES_ILLEGAL );

            //
            // Don't add any privileges until we bind the matching refinement clauses.
            //
            IgnoreFilter    ignorePermissions = new IgnoreFilter();
            getCompilerContext().addPrivilegeFilter( ignorePermissions );
            
            _hojn = new HalfOuterJoinNode
                (
                 _sourceTable,
                 _targetTable,
                 _searchCondition,
                 null,
                 false,
                 null,
                 getContextManager()
                 );

            _leftJoinFromList = _hojn.makeFromList( true, true );
            _leftJoinFromList.bindTables( dd, new FromList( getOptimizerFactory().doJoinOrderOptimization(), getContextManager() ) );

            if ( !sourceIsBase_View_or_VTI() )
            {
                throw StandardException.newException( SQLState.LANG_SOURCE_NOT_BASE_VIEW_OR_VTI );
            }

            FromList    topFromList = new FromList( getOptimizerFactory().doJoinOrderOptimization(), getContextManager() );
            topFromList.addFromTable( _hojn );

            // ready to add permissions
            getCompilerContext().removePrivilegeFilter( ignorePermissions );

            // preliminary binding of the matching clauses to resolve column
            // references. this ensures that we can add all of the columns from
            // the matching refinements to the SELECT list of the left join.
            // we re-bind the matching clauses when we're done binding the left join
            // because, at that time, we have result set numbers needed for
            // code generation.
            for ( MatchingClauseNode mcn : _matchingClauses )
            {
                mcn.bindRefinement( this, _leftJoinFromList );
            }

            ResultColumnList    selectList = buildSelectList();
            
            // save a copy so that we can remap column references when generating the temporary rows
            _selectList = selectList.copyListAndObjects();

            // calculate the offsets into the SELECT list which define the rows for
            // the WHEN [ NOT ] MATCHED  actions
            for ( MatchingClauseNode mcn : _matchingClauses )
            {
                mcn.bindThenColumns( _selectList );
            }

            resultSet = new SelectNode
                (
                 selectList,
                 topFromList,
                 null,      // where clause
                 null,      // group by list
                 null,      // having clause
                 null,      // window list
                 null,      // optimizer plan override
                 getContextManager()
                 );

            // Wrap the SELECT in a CursorNode in order to finish binding it.
            _leftJoinCursor = new CursorNode
                (
                 "SELECT",
                 resultSet,
                 null,
                 null,
                 null,
                 null,
                 false,
                 CursorNode.READ_ONLY,
                 null,
                 getContextManager()
                 );
            
            //
            // We're only interested in privileges related to the ON clause.
            // Otherwise, the driving left join should not contribute any
            // privilege requirements.
            //
            getCompilerContext().addPrivilegeFilter( ignorePermissions );

            _leftJoinCursor.bindStatement();
            
            // ready to add permissions again
            getCompilerContext().removePrivilegeFilter( ignorePermissions );

            // now figure out what privileges are needed for the ON clause
            addOnClausePrivileges();
        }
        finally
        {
            // Restore previous compiler state
            cc.setReliability( previousReliability );
        }
    }

    /** Get the target table for the MERGE statement */
    FromBaseTable   getTargetTable() { return _targetTable; }

    /** Throw a "not base table" exception */
    private void    notBaseTable()  throws StandardException
    {
        throw StandardException.newException( SQLState.LANG_TARGET_NOT_BASE_TABLE );
    }

    /** Build the select list for the left join */
    private ResultColumnList    buildSelectList() throws StandardException
    {
        HashMap<String,ColumnReference> drivingColumnMap = new HashMap<String,ColumnReference>();
        getColumnsInExpression( drivingColumnMap, _searchCondition, ColumnReference.MERGE_UNKNOWN );
        
        for ( MatchingClauseNode mcn : _matchingClauses )
        {
            mcn.getColumnsInExpressions( this, drivingColumnMap );

            int mergeTableID = mcn.isDeleteClause() ? ColumnReference.MERGE_TARGET : ColumnReference.MERGE_UNKNOWN;
            getColumnsFromList( drivingColumnMap, mcn.getBufferedColumns(), mergeTableID );
        }

        ResultColumnList    selectList = new ResultColumnList( getContextManager() );

        // add all of the columns from the source table which are mentioned
        addColumns
            (
             (FromTable) _leftJoinFromList.elementAt( SOURCE_TABLE_INDEX ),
             drivingColumnMap,
             selectList,
             ColumnReference.MERGE_SOURCE
             );
        // add all of the columns from the target table which are mentioned
        addColumns
            (
             (FromTable) _leftJoinFromList.elementAt( TARGET_TABLE_INDEX ),
             drivingColumnMap,
             selectList,
             ColumnReference.MERGE_TARGET
             );

        addTargetRowLocation( selectList );

        return selectList;
    }

    /** Add the target table's row location to the left join's select list */
    private void    addTargetRowLocation( ResultColumnList selectList )
        throws StandardException
    {
        // tell the target table to generate a row location column
        _targetTable.setRowLocationColumnName( TARGET_ROW_LOCATION_NAME );

        TableName   fromTableName = _targetTable.getTableName();
        ColumnReference cr = new ColumnReference
                ( TARGET_ROW_LOCATION_NAME, fromTableName, getContextManager() );
        cr.setMergeTableID( ColumnReference.MERGE_TARGET );
        ResultColumn    rowLocationColumn = new ResultColumn( (String) null, cr, getContextManager() );
        rowLocationColumn.markGenerated();

        selectList.addResultColumn( rowLocationColumn );
    }

    /** Return true if the target table is a base table */
    private boolean targetIsBaseTable( FromBaseTable targetTable ) throws StandardException
    {
        FromBaseTable   fbt = targetTable;
        TableDescriptor desc = fbt.getTableDescriptor();
        if ( desc == null ) { return false; }

        switch( desc.getTableType() )
        {
        case TableDescriptor.BASE_TABLE_TYPE:
        case TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE:
            return true;

        default:
            return false;
        }
    }

    /** Return true if the source table is a base table, view, or table function */
    private boolean sourceIsBase_View_or_VTI() throws StandardException
    {
        if ( _sourceTable instanceof FromVTI ) { return true; }
        if ( !( _sourceTable instanceof FromBaseTable) ) { return false; }

        FromBaseTable   fbt = (FromBaseTable) _sourceTable;
        TableDescriptor desc = fbt.getTableDescriptor();
        if ( desc == null ) { return false; }

        switch( desc.getTableType() )
        {
        case TableDescriptor.BASE_TABLE_TYPE:
        case TableDescriptor.SYSTEM_TABLE_TYPE:
        case TableDescriptor.GLOBAL_TEMPORARY_TABLE_TYPE:
        case TableDescriptor.VIEW_TYPE:
            return true;

        default:
            return false;
        }
    }

    /** Clone a FromTable to avoid binding the original */
    private FromTable   cloneFromTable( FromTable fromTable ) throws StandardException
    {
        if ( fromTable instanceof FromVTI )
        {
            FromVTI source = (FromVTI) fromTable;

            return new FromVTI
                (
                 source.methodCall,
                 source.correlationName,
                 source.getResultColumns(),
                 null,
                 source.exposedName,
                 getContextManager()
                 );
        }
        else if ( fromTable instanceof FromBaseTable )
        {
            FromBaseTable   source = (FromBaseTable) fromTable;
            return new FromBaseTable
                (
                 source.tableName,
                 source.correlationName,
                 null,
                 null,
                 getContextManager()
                 );
        }
        else
        {
            throw StandardException.newException( SQLState.LANG_SOURCE_NOT_BASE_VIEW_OR_VTI );
        }
    }

    /**
     * <p>
     * Add the privileges required by the ON clause.
     * </p>
     */
    private void addOnClausePrivileges() throws StandardException
    {
        // now add USAGE priv on referenced types
        addUDTUsagePriv( getValueNodes( _searchCondition ) );

        // add SELECT privilege on columns
        for ( ColumnReference cr : getColumnReferences( _searchCondition ) )
        {
            addColumnPrivilege( cr );
        }
        
        // add EXECUTE privilege on routines
        for ( StaticMethodCallNode routine : getRoutineReferences( _searchCondition ) )
        {
            addRoutinePrivilege( routine );
        }
    }

    /**
     * <p>
     * Add SELECT privilege on the indicated column.
     * </p>
     */
    private void    addColumnPrivilege( ColumnReference cr )
        throws StandardException
    {
        CompilerContext cc = getCompilerContext();
        ResultColumn    rc = cr.getSource();
        
        if ( rc != null )
        {
            ColumnDescriptor    colDesc = rc.getColumnDescriptor();
            
            if ( colDesc != null )
            {
                cc.pushCurrentPrivType( Authorizer.SELECT_PRIV );
                cc.addRequiredColumnPriv( colDesc );
                cc.popCurrentPrivType();
            }
        }
    }

    /**
     * <p>
     * Add EXECUTE privilege on the indicated routine.
     * </p>
     */
    private void    addRoutinePrivilege( StaticMethodCallNode routine )
        throws StandardException
    {
        CompilerContext cc = getCompilerContext();
        
        cc.pushCurrentPrivType( Authorizer.EXECUTE_PRIV );
        cc.addRequiredRoutinePriv( routine.ad );
        cc.popCurrentPrivType();
    }

    /**
     * <p>
     * Add to an evolving select list the columns from the indicated table.
     * </p>
     */
    private void    addColumns
        (
         FromTable  fromTable,
         HashMap<String,ColumnReference> drivingColumnMap,
         ResultColumnList   selectList,
         int    mergeTableID
         )
        throws StandardException
    {
        String[]    columnNames = getColumns( mergeTableID, drivingColumnMap );
        TableName   tableName = fromTable.getTableName();

        for ( int i = 0; i < columnNames.length; i++ )
        {
            ColumnReference cr = new ColumnReference
                ( columnNames[ i ], tableName, getContextManager() );
            cr.setMergeTableID( mergeTableID );
            ResultColumn    rc = new ResultColumn( (String) null, cr, getContextManager() );
            selectList.addResultColumn( rc );
        }
    }

    /** Get the column names from the table with the given table number, in sorted order */
    private String[]    getColumns( int mergeTableID, HashMap<String,ColumnReference> map )
    {
        ArrayList<String>   list = new ArrayList<String>();

        for ( ColumnReference cr : map.values() )
        {
            if ( cr.getMergeTableID() == mergeTableID ) { list.add( cr.getColumnName() ); }
        }

        String[]    retval = new String[ list.size() ];
        list.toArray( retval );
        Arrays.sort( retval );

        return retval;
    }
    
    /** Add the columns in the matchingRefinement clause to the evolving map */
    void    getColumnsInExpression
        ( HashMap<String,ColumnReference> map, ValueNode expression, int mergeTableID )
        throws StandardException
    {
        if ( expression == null ) { return; }

        List<ColumnReference> colRefs = getColumnReferences( expression );

        getColumnsFromList( map, colRefs, mergeTableID );
    }

    /** Get a list of ValueNodes in an expression */
    private List<ValueNode>   getValueNodes( QueryTreeNode expression )
        throws StandardException
    {
        CollectNodesVisitor<ValueNode> getVNs =
            new CollectNodesVisitor<ValueNode>(ValueNode.class);

        expression.accept(getVNs);
        
        return getVNs.getList();
    }

    /** Get a list of routines in an expression */
    private List<StaticMethodCallNode>   getRoutineReferences( QueryTreeNode expression )
        throws StandardException
    {
        CollectNodesVisitor<StaticMethodCallNode> getSMCNs =
            new CollectNodesVisitor<StaticMethodCallNode>(StaticMethodCallNode.class);

        expression.accept(getSMCNs);
        
        return getSMCNs.getList();
    }

    /** Get a list of column references in an expression */
    private List<ColumnReference>   getColumnReferences( QueryTreeNode expression )
        throws StandardException
    {
        CollectNodesVisitor<ColumnReference> getCRs =
            new CollectNodesVisitor<ColumnReference>(ColumnReference.class);

        expression.accept(getCRs);
        
        return getCRs.getList();
    }

    /** Add a list of columns to the the evolving map */
    void    getColumnsFromList
        ( HashMap<String,ColumnReference> map, ResultColumnList rcl, int mergeTableID )
        throws StandardException
    {
        List<ColumnReference> colRefs = getColumnReferences( rcl );

        getColumnsFromList( map, colRefs, mergeTableID );
    }
    
    /** Add a list of columns to the the evolving map */
    private void    getColumnsFromList
        ( HashMap<String,ColumnReference> map, List<ColumnReference> colRefs, int mergeTableID )
        throws StandardException
    {
        for ( ColumnReference cr : colRefs )
        {
            addColumn( map, cr, mergeTableID );
        }
    }

    /** Add a column to the evolving map of referenced columns */
    void    addColumn
        (
         HashMap<String,ColumnReference> map,
         ColumnReference    cr,
         int    mergeTableID
         )
        throws StandardException
    {
        if ( cr.getTableName() == null )
        {
            ResultColumn    rc = _leftJoinFromList.bindColumnReference( cr );
            TableName       tableName = cr.getQualifiedTableName();
            if ( tableName == null ) { tableName = new TableName( null, rc.getTableName(), getContextManager() ); }
            cr = new ColumnReference( cr.getColumnName(), tableName, getContextManager() );
        }

        associateColumn( _leftJoinFromList, cr, mergeTableID );

        String  key = makeDCMKey( cr.getTableName(), cr.getColumnName() );

        ColumnReference mapCR = map.get( key );
        if ( mapCR != null )
        {
            mapCR.setMergeTableID( cr.getMergeTableID() );
        }
        else
        {
            map.put( key, cr );
        }
    }

    /** Associate a column with the SOURCE or TARGET table */
    void    associateColumn( FromList fromList, ColumnReference cr, int mergeTableID )
        throws StandardException
    {
        if ( mergeTableID != ColumnReference.MERGE_UNKNOWN )    { cr.setMergeTableID( mergeTableID ); }
        else
        {
            // we have to figure out which table the column is in
            String  columnsTableName = cr.getTableName();

            if ( ((FromTable) fromList.elementAt( SOURCE_TABLE_INDEX )).getMatchingColumn( cr ) != null )
            {
                cr.setMergeTableID( ColumnReference.MERGE_SOURCE );
            }
            else if ( ((FromTable) fromList.elementAt( TARGET_TABLE_INDEX )).getMatchingColumn( cr ) != null )
            {
                cr.setMergeTableID( ColumnReference.MERGE_TARGET );
            }
        }

        // Don't raise an error if a column in another table is referenced and we
        // don't know how to handle it here. If the column is not in the SOURCE or TARGET
        // table, then it will be caught by other bind-time logic. Columns which ought
        // to be associated, but aren't, will be caught later on by MatchingClauseNode.getMergeTableID().
    }

    /** Make a HashMap key for a column in the driving column map of the LEFT JOIN */
    private String  makeDCMKey( String tableName, String columnName )
    {
        return IdUtil.mkQualifiedName( tableName, columnName );
    }

    /** Boilerplate for binding an expression against a FromList */
    void bindExpression( ValueNode value, FromList fromList )
        throws StandardException
    {
        CompilerContext cc = getCompilerContext();
        final int previousReliability = cc.getReliability();

        cc.setReliability( previousReliability | CompilerContext.SQL_IN_ROUTINES_ILLEGAL );
        cc.pushCurrentPrivType( Authorizer.SELECT_PRIV );
            
        try {
            // this adds SELECT priv on referenced columns and EXECUTE privs on referenced routines
            value.bindExpression
                (
                 fromList,
                 new SubqueryList( getContextManager() ),
                 new ArrayList<AggregateNode>()
                 );

            // now add USAGE priv on referenced types
            addUDTUsagePriv( getValueNodes( value ) );
        }
        finally
        {
            // Restore previous compiler state
            cc.popCurrentPrivType();
            cc.setReliability( previousReliability );
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // optimize() BEHAVIOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

    @Override
	public void optimizeStatement() throws StandardException
	{
        //
        // Don't add any privileges during optimization.
        //
        IgnoreFilter    ignorePermissions = new IgnoreFilter();
        getCompilerContext().addPrivilegeFilter( ignorePermissions );
            
		/* First optimize the left join */
		_leftJoinCursor.optimizeStatement();

		/* In language we always set it to row lock, it's up to store to
		 * upgrade it to table lock.  This makes sense for the default read
		 * committed isolation level and update lock.  For more detail, see
		 * Beetle 4133.
		 */
		//lockMode = TransactionController.MODE_RECORD;

        // now optimize the INSERT/UPDATE/DELETE actions
        for ( MatchingClauseNode mcn : _matchingClauses )
        {
            mcn.optimize();
        }
        
        // ready to add permissions again
        getCompilerContext().removePrivilegeFilter( ignorePermissions );
	}
    ///////////////////////////////////////////////////////////////////////////////////
    //
    // generate() BEHAVIOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

    @Override
    void generate( ActivationClassBuilder acb, MethodBuilder mb )
							throws StandardException
	{
        int     clauseCount = _matchingClauses.size();

		/* generate the parameters */
		generateParameterValueSet(acb);

        acb.pushGetResultSetFactoryExpression( mb );

        // arg 1: the driving left join 
        _leftJoinCursor.generate( acb, mb );

        // dig up the actual result set which was generated and which will drive the MergeResultSet
        ScrollInsensitiveResultSetNode  sirs = (ScrollInsensitiveResultSetNode) _leftJoinCursor.resultSet;
        ResultSetNode   generatedScan = sirs.getChildResult();

        ConstantAction[]    clauseActions = new ConstantAction[ clauseCount ];
        for ( int i = 0; i < clauseCount; i++ )
        {
            MatchingClauseNode  mcn = _matchingClauses.get( i );

            mcn.generate( acb, _selectList, generatedScan, _hojn, i );
            clauseActions[ i ] = mcn.makeConstantAction( acb );
        }
        _constantAction = getGenericConstantActionFactory().getMergeConstantAction( clauseActions );
        
        mb.callMethod
            ( VMOpcode.INVOKEINTERFACE, (String) null, "getMergeResultSet", ClassName.ResultSet, 1 );
	}
    
    @Override
    public ConstantAction makeConstantAction() throws StandardException
	{
		return _constantAction;
	}

    ///////////////////////////////////////////////////////////////////////////////////
    //
    // Visitable BEHAVIOR
    //
    ///////////////////////////////////////////////////////////////////////////////////

	/**
	 * Accept the visitor for all visitable children of this node.
	 * 
	 * @param v the visitor
	 *
	 * @exception StandardException on error
	 */
    @Override
	void acceptChildren(Visitor v)
		throws StandardException
	{
        if ( _leftJoinCursor != null )
        {
            _leftJoinCursor.acceptChildren( v );
        }
        else
        {
            super.acceptChildren( v );

            _targetTable.accept( v );
            _sourceTable.accept( v );
            _searchCondition.accept( v );
        }
        
        for ( MatchingClauseNode mcn : _matchingClauses )
        {
            mcn.accept( v );
        }
	}

    @Override
    String statementToString()
	{
		return "MERGE";
	}
}