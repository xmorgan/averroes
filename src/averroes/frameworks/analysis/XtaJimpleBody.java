package averroes.frameworks.analysis;

import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import soot.ArrayType;
import soot.BooleanType;
import soot.Local;
import soot.Modifier;
import soot.RefLikeType;
import soot.RefType;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.Type;
import soot.Value;
import soot.VoidType;
import soot.jimple.ArrayRef;
import soot.jimple.EqExpr;
import soot.jimple.IntConstant;
import soot.jimple.InterfaceInvokeExpr;
import soot.jimple.InvokeExpr;
import soot.jimple.Jimple;
import soot.jimple.NopStmt;
import soot.jimple.SpecialInvokeExpr;
import soot.jimple.StaticInvokeExpr;
import soot.jimple.Stmt;
import soot.jimple.ThrowStmt;
import soot.jimple.VirtualInvokeExpr;
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
	protected final Logger logger = LoggerFactory.getLogger(getClass());
	
	private Local setM = null;
	private Local xtaGuard = null;

	/**
	 * Create a new XTA Jimple body creator for method M.
	 * 
	 * @param method
	 */
	public XtaJimpleBody(SootMethod method) {
		super(method);
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
	 * Create all the objects that the library could possible instantiate. For
	 * reference types, this includes inserting new statements, invoking
	 * constructors, and static initializers if found. For arrays, we just have
	 * the appropriate NEW instruction. For checked exceptions, we create the
	 * object, call the constructor, and, if available, call the static
	 * initializer.
	 */
	private void createObjects() {
		objectCreations.forEach(e -> {
			SootMethod init = e.getMethod();
			SootClass cls = init.getDeclaringClass();
			Local obj = createObjectByMethod(cls, init);
			storeToRtaSet(obj);
		});

		arrayCreations.forEach(t -> {
			Local obj = insertNewStmt(t);
			storeToRtaSet(obj);
		});

		checkedExceptions.forEach(cls -> {
			SootMethod init = cls.getMethod(Names.DEFAULT_CONSTRUCTOR_SUBSIG);
			Local obj = createObjectByMethod(cls, init);
			storeToRtaSet(obj);
		});
	}

	/**
	 * Create an object by calling that specific constructor.
	 * 
	 * @param cls
	 * @param init
	 * @return
	 */
	private Local createObjectByMethod(SootClass cls, SootMethod init) {
		Local obj = insertNewStmt(RefType.v(cls));
		insertSpecialInvokeStmt(obj, init, getSetM());

		// Call <clinit> if found
		if (cls.declaresMethod(SootMethod.staticInitializerName)) {
			insertStaticInvokeStmt(cls
					.getMethodByName(SootMethod.staticInitializerName));
		}

		return obj;
	}

	/**
	 * Call the methods that are called in the original method body. Averroes
	 * preserves the fact that the return value of some method calls
	 * (invokeExprs) is stored locally, while it's not for other calls
	 * (invokeStmts).
	 */
	private void callMethods() {
		invokeStmts.forEach(this::insertInvokeStmt);

		invokeExprs.forEach(e -> {
			InvokeExpr expr = buildInvokeExpr(e);
			// Only store the return value if it's a reference, otherwise just
			// call the method.
				if (expr.getMethod().getReturnType() instanceof RefLikeType) {
					storeMethodCallReturn(expr);
				} else {
					insertInvokeStmt(expr);
				}
			});
	}

	/**
	 * Handle array reads and writes.
	 */
	private void handleArrays() {
		if (readsArray || writesArray) {
			Local cast = (Local) getCompatibleValue(getSetM(),
					ArrayType.v(getSetM().getType(), ARRAY_LENGTH.value));
			ArrayRef arrayRef = Jimple.v().newArrayRef(cast, ARRAY_INDEX);

			if (readsArray) {
				body.getUnits().add(
						Jimple.v().newAssignStmt(getSetM(), arrayRef));
			} else {
				body.getUnits().add(
						Jimple.v().newAssignStmt(arrayRef, getSetM()));
			}
		}
	}

	/**
	 * Handle throwing exceptions and try-catch blocks.
	 */
	private void handleExceptions() {
		throwables.forEach(x -> insertThrowStmt(x, throwables.size() > 1));
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
	 * Insert the identity statements, and assign actual parameters (if any) and
	 * the this parameter (if any) to set_m
	 */
	private void insertJimpleBodyHeader() {
		body.insertIdentityStmts();

		/*
		 * To generate correct bytecode, we need to initialize the object first
		 * by calling the direct superclass default constructor before inserting
		 * any more statements. That is if this method is for a constructor and
		 * its declaring class has a superclass.
		 */
		if (method.isConstructor()
				&& method.getDeclaringClass().hasSuperclass()) {
			Local base = body.getThisLocal();
			insertSpecialInvokeStmt(base, method.getDeclaringClass()
					.getSuperclass()
					.getMethod(Names.DEFAULT_CONSTRUCTOR_SUBSIG), getSetM());
		}

		assignMethodParameters();
	}

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
			Value ret = getCompatibleValue(getSetM(), method.getReturnType());
			body.getUnits().add(Jimple.v().newReturnStmt(ret));
		}
	}

	/**
	 * Store a value to RTA.set
	 * 
	 * @param value
	 */
	private void storeToRtaSet(Value value) {
		storeStaticField(Scene.v().getField(Names.RTA_SET_FIELD_SIGNATURE),
				value);
	}

	/**
	 * Store the return value of a method call to RTA.set
	 * 
	 * @param value
	 */
	private void storeMethodCallReturn(InvokeExpr expr) {
		Local ret = localGenerator.generateLocal(expr.getMethod()
				.getReturnType());
		body.getUnits().add(Jimple.v().newAssignStmt(ret, expr));
		storeToRtaSet(ret);
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
	 * Load the RTA.guard field into a local variable, if not loaded before.
	 * 
	 * @return
	 */
	private Local getGuard() {
		if (xtaGuard == null) {
			xtaGuard = loadStaticField(Scene.v().getField(
					Names.RTA_GUARD_FIELD_SIGNATURE));
		}
		return xtaGuard;
	}

	/**
	 * Construct Jimple code that assigns method parameters, including the
	 * "this" parameter, if available.
	 */
	private void assignMethodParameters() {
		// Assign the "this" parameter, if available
		if (!method.isStatic()) {
			storeToRtaSet(body.getThisLocal());
		}

		// Loop over all parameters of reference type and create an assignment
		// statement to the appropriate "expression".
		body.getParameterLocals().stream()
				.filter(l -> l.getType() instanceof RefLikeType)
				.forEach(l -> storeToRtaSet(l));
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

	/**
	 * Insert a throw statement that throws an exception of the given type.
	 * 
	 * @param type
	 */
	protected void insertThrowStmt(Type type, boolean guard) {
		Local tmp = insertCastStmt(getSetM(), type);
		if (guard) {
			insertAndGuardStmt(Jimple.v().newThrowStmt(tmp));
		} else {
			body.getUnits().add(Jimple.v().newThrowStmt(tmp));
		}
	}

	/**
	 * Insert a static invoke statement.
	 * 
	 * @param method
	 */
	protected void insertStaticInvokeStmt(SootMethod method) {
		List<Value> args = method.getParameterTypes().stream()
				.map(p -> getCompatibleValue(getSetM(), p))
				.collect(Collectors.toList());
		body.getUnits()
				.add(Jimple.v().newInvokeStmt(
						Jimple.v().newStaticInvokeExpr(method.makeRef(), args)));
	}

	/**
	 * Insert a new invoke statement based on info in the given original invoke
	 * experssion.
	 * 
	 * @param originalInvokeExpr
	 */
	protected void insertInvokeStmt(InvokeExpr originalInvokeExpr) {
		body.getUnits().add(
				Jimple.v().newInvokeStmt(buildInvokeExpr(originalInvokeExpr)));
	}

	/**
	 * Build the grammar of an invoke expression based on the given original
	 * invoke expression. This method does not insert the grammar chunk into the
	 * method body. It only inserts any code needed to prepare the arguments to
	 * the call (i.e., casts of RTA.set).
	 * 
	 * @param originalInvokeExpr
	 * @return
	 */
	private InvokeExpr buildInvokeExpr(InvokeExpr originalInvokeExpr) {
		SootMethod callee = originalInvokeExpr.getMethod();
		InvokeExpr invokeExpr = null;

		// Get the arguments to the call
		List<Value> args = originalInvokeExpr.getArgs().stream()
				.map(a -> getCompatibleValue(getSetM(), a.getType()))
				.collect(Collectors.toList());

		// Build the invoke expression
		if (originalInvokeExpr instanceof StaticInvokeExpr) {
			invokeExpr = Jimple.v().newStaticInvokeExpr(callee.makeRef(), args);
		} else if (originalInvokeExpr instanceof SpecialInvokeExpr) {
			Local base = (Local) getCompatibleValue(getSetM(),
					((SpecialInvokeExpr) originalInvokeExpr).getBase()
							.getType());
			invokeExpr = Jimple.v().newSpecialInvokeExpr(base,
					callee.makeRef(), args);
		} else if (originalInvokeExpr instanceof InterfaceInvokeExpr) {
			Local base = (Local) getCompatibleValue(getSetM(),
					((InterfaceInvokeExpr) originalInvokeExpr).getBase()
							.getType());
			invokeExpr = Jimple.v().newInterfaceInvokeExpr(base,
					callee.makeRef(), args);
		} else if (originalInvokeExpr instanceof VirtualInvokeExpr) {
			Local base = (Local) getCompatibleValue(getSetM(),
					((VirtualInvokeExpr) originalInvokeExpr).getBase()
							.getType());
			invokeExpr = Jimple.v().newVirtualInvokeExpr(base,
					callee.makeRef(), args);
		} else {
			logger.error("Cannot handle invoke expression of type: "
					+ originalInvokeExpr.getClass());
		}

		return invokeExpr;
	}
}