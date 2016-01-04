package averroes.frameworks.analysis;

import soot.BooleanType;
import soot.Local;
import soot.Modifier;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Value;
import soot.VoidType;
import soot.jimple.EqExpr;
import soot.jimple.IntConstant;
import soot.jimple.Jimple;
import soot.jimple.NopStmt;
import soot.jimple.Stmt;
import soot.jimple.ThrowStmt;
import averroes.frameworks.soot.CodeGenerator;
import averroes.soot.Names;
import averroes.util.io.Printers;
import averroes.util.io.Printers.PrinterType;

/**
 * XTA Jimple body creator that over-approximates objects in the library by
 * using one set per method (set_m) and one set per field (set_f). The analysis
 * also generates a class xta.XTA that holds some commons values (e.g., guard
 * used in conditions).
 * 
 * @author Karim Ali
 *
 */
public class XtaJimpleBody extends AbstractJimpleBody {
	private Local setM = null;
	private Local xtaGuard = null;

	/**
	 * Create a new XTA Jimple body creator for method M.
	 * 
	 * @param method
	 */
	public XtaJimpleBody(SootMethod method) {
		super(method);
		setM = localGenerator.generateLocal(Scene.v().getObjectType());
	}

	@Override
	public void generateCode() {
		// TODO
		Printers.print(PrinterType.ORIGINAL, method);

		// Create XTA Class
		ensureXtaClassExists();

		// Create the new Jimple body
		insertJimpleBodyHeader();
		createObjects();
		callMethods();
		handleArrays();
		// handleFields();
		handleExceptions();
		insertJimpleBodyFooter();

		// Cleanup the generated body
		cleanup();

		// Validate method Jimple body & assign it to the method
		body.validate();
		method.setActiveBody(body);

		// TODO
		Printers.print(PrinterType.GENERATED, method);
	}

	@Override
	public Local setToCast() {
		return getSetM();
	}

	@Override
	public void storeToSet(Value from) {
		body.getUnits().add(Jimple.v().newAssignStmt(getSetM(), from));
	}

	@Override
	public Local getGuard() {
		if (xtaGuard == null) {
			xtaGuard = loadStaticField(Scene.v().getField(
					Names.RTA_GUARD_FIELD_SIGNATURE));
		}
		return xtaGuard;
	}

	/**
	 * Ensure that the XTA class has been created, along with its fields.
	 */
	private void ensureXtaClassExists() {
		if (Scene.v().containsClass(Names.XTA_CLASS)) {
			return;
		}

		// Create a public class and set its super class to java.lang.Object
		SootClass averroesXta = CodeGenerator.createEmptyClass(Names.XTA_CLASS,
				Modifier.PUBLIC, Scene.v().getObjectType().getSootClass());

		// Add a default constructor to it
		CodeGenerator.createEmptyDefaultConstructor(averroesXta);

		// Add static field "guard" to the class
		CodeGenerator.createField(averroesXta, Names.GUARD_FIELD_NAME,
				BooleanType.v(), Modifier.PUBLIC | Modifier.STATIC);

		// Add a static initializer to it (it also initializes the static fields
		// with default values
		CodeGenerator.createStaticInitializer(averroesXta);

		// TODO: Write it to disk
		averroesXta.getMethods().forEach(
				m -> Printers.print(PrinterType.GENERATED, m));
		// ClassWriter.writeLibraryClassFile(averroesRta);
	}

	/**
	 * Insert identity statements. The version in {@link JimpleBody} does not
	 * use the {@link LocalGenerator} class to generate locals (it actually
	 * can't because it doesn't have a reference to the {@link LocalGenerator}
	 * that was used to generated this method body). This causes inconsistency
	 * in the names of the generated local variables and this method tries to
	 * fix that.
	 */
	// private void insertIdentityStmts() {
	// // Add "this" before anything else
	// if (!method.isStatic()) {
	// Local r0 = localGenerator.generateLocal(method.getDeclaringClass()
	// .getType());
	// ThisRef thisRef = Jimple.v().newThisRef((RefType) r0.getType());
	// body.getUnits().addFirst(
	// Jimple.v().newIdentityStmt(thisRef, thisRef));
	// }
	//
	// // Add identity statements for any method parameters next
	// for (int i = 0; i < method.getParameterCount(); i++) {
	// Local p = localGenerator.generateLocal(method.getParameterType(i));
	// ParameterRef paramRef = Jimple.v().newParameterRef(p.getType(), i);
	// body.getUnits().add(Jimple.v().newIdentityStmt(p, paramRef));
	// }
	// }

	/**
	 * Insert the standard footer for a library method.
	 */
	private void insertJimpleBodyFooter() {
		/*
		 * Insert the return statement, only if there are no throwables to
		 * throw. Otherwise, it's dead code and the Jimple validator will choke
		 * on it!
		 */
		if (throwables.isEmpty()) {
			insertReturnStmt();
		}
	}

	/**
	 * Insert the appropriate return statement.
	 */
	private void insertReturnStmt() {
		if (method.getReturnType() instanceof VoidType) {
			body.getUnits().add(Jimple.v().newReturnVoidStmt());
		} else {
			Value ret = getCompatibleValue(method.getReturnType());
			body.getUnits().add(Jimple.v().newReturnStmt(ret));
		}
	}

	/**
	 * Return the local for set_m.
	 * 
	 * @return
	 */
	private Local getSetM() {
		return setM;
	}

	/**
	 * Guard a statement by an if-statement whose condition always evaluates to
	 * true. This helps inserting multiple {@link ThrowStmt} for example in a
	 * Jimple method.
	 * 
	 * @param stmt
	 * @return
	 */
	protected void insertAndGuardStmt(Stmt stmt) {
		// TODO: this condition can produce dead code. That's why we should use
		// the RTA.guard field as a condition instead.
		// NeExpr cond = Jimple.v().newNeExpr(IntConstant.v(1),
		// IntConstant.v(1));
		EqExpr cond = Jimple.v().newEqExpr(getGuard(), IntConstant.v(0));
		NopStmt nop = Jimple.v().newNopStmt();

		body.getUnits().add(Jimple.v().newIfStmt(cond, nop));
		body.getUnits().add(stmt);
		body.getUnits().add(nop);
	}
}
