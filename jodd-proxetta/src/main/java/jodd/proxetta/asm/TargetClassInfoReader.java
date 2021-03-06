// Copyright (c) 2003-2014, Jodd Team (jodd.org). All Rights Reserved.

package jodd.proxetta.asm;

import jodd.asm.AsmUtil;
import jodd.asm4.MethodVisitor;
import jodd.asm4.ClassReader;
import jodd.asm4.AnnotationVisitor;
import jodd.asm4.signature.SignatureReader;

import java.util.Iterator;
import java.util.Set;
import java.util.HashSet;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.ArrayList;
import java.io.InputStream;
import java.io.IOException;

import static jodd.proxetta.asm.ProxettaAsmUtil.INIT;
import static jodd.proxetta.asm.ProxettaAsmUtil.CLINIT;
import jodd.proxetta.ProxettaException;
import jodd.proxetta.ClassInfo;
import jodd.proxetta.AnnotationInfo;
import jodd.util.ClassLoaderUtil;
import jodd.io.StreamUtil;
import jodd.asm.EmptyClassVisitor;
import jodd.asm.EmptyMethodVisitor;

/**
 * Reads info from target class.
 */
@SuppressWarnings({"AnonymousClassVariableHidesContainingMethodVariable"})
public class TargetClassInfoReader extends EmptyClassVisitor implements ClassInfo {

	//protected ClassInfo classInfo;

	protected final Map<String, MethodSignatureVisitor> methodSignatures;
	protected final List<ClassReader> superClassReaders;					// list of all super class readers
	protected final Set<String> allMethodSignatures;

	public TargetClassInfoReader() {
		this.methodSignatures = new HashMap<String, MethodSignatureVisitor>();
		this.superClassReaders = new ArrayList<ClassReader>();
		this.allMethodSignatures = new HashSet<String>();
	}


	// ---------------------------------------------------------------- some getters

	/**
	 * Returns method signature for some method. If signature is not found, returns <code>null</code>.
	 * Founded signatures means that those method can be proxyfied.
	 */
	public MethodSignatureVisitor lookupMethodSignatureVisitor(int access, String name, String desc, String className) {
		String key = ProxettaAsmUtil.createMethodSignaturesKey(access, name, desc, className);
		return methodSignatures.get(key);
	}

	/**
	 * Returns <code>true</code> if method is marked for proxy.
	 */
	public boolean isMarkedForProxy(MethodSignatureVisitor msgin) {
		return allMethodSignatures.contains(msgin.getSignature());
	}

	// ---------------------------------------------------------------- information

	protected String targetPackage;
	protected String targetClassname;
	protected String superName;
	protected String thisReference;
	protected String nextSupername;
	protected String[] superClasses;
	protected int hierarchyLevel;
	protected AnnotationInfo[] annotations;
	protected List<AnnotationInfo> classAnnotations;
	protected boolean isTargetIntreface;
	protected Set<String> nextInterfaces;

	// ---------------------------------------------------------------- class interface

	public String getPackage() {
		return targetPackage;
	}

	public String getClassname() {
		return targetClassname;
	}

	public String getSuperName() {
		return superName;
	}

	public String getReference() {
		return thisReference;
	}

	public String[] getSuperClasses() {
		return superClasses;
	}

	public AnnotationInfo[] getAnnotations() {
		return annotations;
	}

	// ---------------------------------------------------------------- visits


	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
		int lastSlash = name.lastIndexOf('/');
		this.thisReference = name;
		this.superName = superName;
		this.nextSupername = superName;
		this.targetPackage = name.substring(0, lastSlash).replace('/', '.');
		this.targetClassname = name.substring(lastSlash + 1);
		this.hierarchyLevel = 1;

		this.isTargetIntreface = (access & AsmUtil.ACC_INTERFACE) != 0;
		if (this.isTargetIntreface) {
			nextInterfaces = new HashSet<String>();
			if (interfaces != null) {
				for (String inter : interfaces) {
					nextInterfaces.add(inter);
				}
			}
		}
	}


	@Override
	public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
		AnnotationReader ar = new AnnotationReader(desc, visible);
		if (classAnnotations == null) {
			classAnnotations = new ArrayList<AnnotationInfo>();
		}
		classAnnotations.add(ar);
		return ar;
	}

	/**
	 * Stores method signature for target method.
	 */
	@Override
	public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
		if ((access & AsmUtil.ACC_FINAL) != 0) {
			return null;	// skip finals
		}
		MethodSignatureVisitor msign = createMethodSignature(access, name, desc, signature, thisReference);
		String key = ProxettaAsmUtil.createMethodSignaturesKey(access, name, desc, thisReference);
		methodSignatures.put(key, msign);
		allMethodSignatures.add(msign.getSignature());
		return new MethodAnnotationReader(msign);
	}

	/**
	 * Stores signatures for all super public methods not already overridden by target class.
	 * All this methods will be accepted for proxyfication.
	 */
	@Override
	public void visitEnd() {

		// prepare class annotations
		if (classAnnotations != null) {
			annotations = classAnnotations.toArray(new AnnotationInfo[classAnnotations.size()]);
			classAnnotations = null;
		}


		List<String> superList = new ArrayList<String>();
		// check all public super methods that are not overridden in superclass
		while (nextSupername != null) {
			InputStream inputStream = null;
			ClassReader cr = null;
			try {
				inputStream = ClassLoaderUtil.getClassAsStream(nextSupername);
				cr = new ClassReader(inputStream);
			} catch (IOException ioex) {
				throw new ProxettaException("Unable to inspect super class: " + nextSupername, ioex);
			} finally {
				StreamUtil.close(inputStream);
			}
			hierarchyLevel++;
			superList.add(nextSupername);
			superClassReaders.add(cr);	// remember the super class reader
			cr.accept(new SuperClassVisitor(), 0);
		}
		superClasses = superList.toArray(new String[superList.size()]);

		// check all interface methods that are not overridden in super-interface
		if (nextInterfaces != null) {
			while (!nextInterfaces.isEmpty()) {
				Iterator<String> iterator = nextInterfaces.iterator();
				String next = iterator.next();
				iterator.remove();

				InputStream inputStream = null;
				ClassReader cr = null;
				try {
					inputStream = ClassLoaderUtil.getClassAsStream(next);
					cr = new ClassReader(inputStream);
				} catch (IOException ioex) {
					throw new ProxettaException("Unable to inspect super interface: " + next, ioex);
				} finally {
					StreamUtil.close(inputStream);
				}
				hierarchyLevel++;
				superClassReaders.add(cr);				// remember the super class reader
				cr.accept(new SuperClassVisitor(), 0);
			}
		}

	}


	/**
	 * Creates method signature from method name.
	 */
	protected MethodSignatureVisitor createMethodSignature(int access, String methodName, String description, String signature, String classname) {
		MethodSignatureVisitor v = new MethodSignatureVisitor(methodName, access, classname, description, signature, this);
		v.hierarchyLevel = this.hierarchyLevel;
		new SignatureReader(signature != null ? signature : description).accept(v);
		return v;
	}


	// ---------------------------------------------------------------- util class

	/**
	 * Reads method annotations and stores to method info.
	 */
	static class MethodAnnotationReader extends EmptyMethodVisitor {

		final List<AnnotationInfo> methodAnns = new ArrayList<AnnotationInfo>();
		final MethodSignatureVisitor msign;

		MethodAnnotationReader(MethodSignatureVisitor msign) {
			this.msign = msign;
		}

		@Override
		public AnnotationVisitor visitAnnotation(String desc, boolean visible) {
			AnnotationReader ar = new AnnotationReader(desc, visible);
			methodAnns.add(ar);
			return ar;
		}

		@Override
		public void visitEnd() {
			if (methodAnns.isEmpty() == false) {
				msign.annotations = methodAnns.toArray(new AnnotationInfo[methodAnns.size()]);
			}
		}
	}

	// ---------------------------------------------------------------- super class visitor

	private class SuperClassVisitor extends EmptyClassVisitor {

		String declaredClassName;

		@Override
		public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
			nextSupername = superName;
			declaredClassName = name;

			// append inner interfaces
			if (nextInterfaces != null) {
				if (interfaces != null) {
					for (String inter : interfaces) {
						nextInterfaces.add(inter);
					}
				}
			}

		}

		@Override
		public MethodVisitor visitMethod(int access, String name, String desc, String signature, String[] exceptions) {
			if (name.equals(INIT) || name.equals(CLINIT)) {
				return null;
			}
			MethodSignatureVisitor msign = createMethodSignature(access, name, desc, signature, thisReference);
			int acc = msign.getAccessFlags();
			if ((acc & AsmUtil.ACC_PUBLIC) == 0) {   	// skip non-public
				return null;
			}
			if ((access & AsmUtil.ACC_FINAL) != 0) {		// skip finals
				return null;
			}
			if (allMethodSignatures.contains(msign.getSignature())) {		// skip overridden method by some in above classes
				return null;
			}

			msign.setDeclaredClassName(declaredClassName);		// indicates it is not a top level class
			String key = ProxettaAsmUtil.createMethodSignaturesKey(access, name, desc, declaredClassName);
			methodSignatures.put(key, msign);
			allMethodSignatures.add(msign.getSignature());
			return new MethodAnnotationReader(msign);
		}
	}

	// ---------------------------------------------------------------- toString


	@Override
	public String toString() {
		return "target: " + this.targetPackage + '.' + this.targetClassname;
	}
}
