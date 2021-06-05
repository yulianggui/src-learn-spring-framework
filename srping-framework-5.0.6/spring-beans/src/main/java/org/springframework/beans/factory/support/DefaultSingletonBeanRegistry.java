/*
 * Copyright 2002-2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.beans.factory.support;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanCreationException;
import org.springframework.beans.factory.BeanCreationNotAllowedException;
import org.springframework.beans.factory.BeanCurrentlyInCreationException;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.ObjectFactory;
import org.springframework.beans.factory.config.SingletonBeanRegistry;
import org.springframework.core.SimpleAliasRegistry;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

/**
 * Generic registry for shared bean instances, implementing the
 * {@link org.springframework.beans.factory.config.SingletonBeanRegistry}.
 * Allows for registering singleton instances that should be shared
 * for all callers of the registry, to be obtained via bean name.
 *
 * <p>Also supports registration of
 * {@link org.springframework.beans.factory.DisposableBean} instances,
 * (which might or might not correspond to registered singletons),
 * to be destroyed on shutdown of the registry. Dependencies between
 * beans can be registered to enforce an appropriate shutdown order.
 *
 * <p>This class mainly serves as base class for
 * {@link org.springframework.beans.factory.BeanFactory} implementations,
 * factoring out the common management of singleton bean instances. Note that
 * the {@link org.springframework.beans.factory.config.ConfigurableBeanFactory}
 * interface extends the {@link SingletonBeanRegistry} interface.
 *
 * <p>Note that this class assumes neither a bean definition concept
 * nor a specific creation process for bean instances, in contrast to
 * {@link AbstractBeanFactory} and {@link DefaultListableBeanFactory}
 * (which inherit from it). Can alternatively also be used as a nested
 * helper to delegate to.
 *
 * DefaultSingletonBeanRegistry类继承了SimpleAliasRegistry以及实现了SingletonBeanRegistry的接口。处理Bean的注册,销毁,以及依赖关系的注册和销毁
 *	https://segmentfault.com/a/1190000020902508
 * @author Juergen Hoeller
 * @since 2.0
 * @see #registerSingleton
 * @see #registerDisposableBean
 * @see org.springframework.beans.factory.DisposableBean
 * @see org.springframework.beans.factory.config.ConfigurableBeanFactory
 */
public class DefaultSingletonBeanRegistry extends SimpleAliasRegistry implements SingletonBeanRegistry {

	/** Logger available to subclasses */
	protected final Log logger = LogFactory.getLog(getClass());

	/** Cache of singleton objects: bean name --> bean instance */
	/**
	 * 存放的是单例 bean 的映射
	 * 对应关系为 bean name --> bean instance
	 * 单例对象的缓存:从beanname到bean实例
	 */
	private final Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

	/** Cache of singleton factories: bean name --> ObjectFactory */
	/**
	 * 存放的是 ObjectFactory，可以理解为创建单例 bean 的 factory
	 * 对应关系是 bean name --> ObjectFactory
	 * 单例工厂的缓存:从beanname到ObjectFactory
	 *
	 * 这个也是 解析 单例 循环依赖的关键所在
	 *
	 */
	private final Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);

	/** Cache of early singleton objects: bean name --> bean instance */
	/**
	 *
	 *	存放的是早期的 bean，对应关系也是 bean name --> bean instance
	 *  它与 {@link #singletonFactories} 区别在于 earlySingletonObjects 中存放的 bean 不一定是完整
	 *  从 {@link #getSingleton(String)} 方法中，我们可以了解，bean 在创建过程中就已经加入到 earlySingletonObjects 中
	 *  所以当在 bean 的创建过程中，就可以通过 getBean() 方法获取
	 *
	 *  早期单例对象的缓存:从beanname到bean实例
	 */
	private final Map<String, Object> earlySingletonObjects = new HashMap<>(16);

	/** Set of registered singletons, containing the bean names in registration order */
	/**
	 *  一组已注册的单例，包含按注册顺序排列的beanname
	 */
	private final Set<String> registeredSingletons = new LinkedHashSet<>(256);

	/** Names of beans that are currently in creation */
	/**
	 * 单例bean 是否正在创建中 -- 解决循环依赖的其中一个 set 缓存
	 */
	private final Set<String> singletonsCurrentlyInCreation =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** Names of beans currently excluded from in creation checks */
	/**
	 * 当前在创建检查中排除的bean名称
	 * 当前不检查的bean的集合
	 *
	 *    什么时候进行添加的？？？
	 */
	private final Set<String> inCreationCheckExclusions =
			Collections.newSetFromMap(new ConcurrentHashMap<>(16));

	/** List of suppressed Exceptions, available for associating related causes */
	/**
	 * 异常集合
	 */
	@Nullable
	private Set<Exception> suppressedExceptions;

	/** Flag that indicates whether we're currently within destroySingletons */
	/**
	 * 当前是否在销毁bean中
	 */
	private boolean singletonsCurrentlyInDestruction = false;

	/** Disposable bean instances: bean name --> disposable instance */
	/**
	 * 一次性bean实例
	 * beanName -> 消耗的bean
	 */
	private final Map<String, Object> disposableBeans = new LinkedHashMap<>();

	/** Map between containing bean names: bean name --> Set of bean names that the bean contains */
	/**
	 * 内部bean和外部bean之间关系
	 */
	private final Map<String, Set<String>> containedBeanMap = new ConcurrentHashMap<>(16);

	/** Map between dependent bean names: bean name --> Set of dependent bean names */
	/**
	 * 对象保存的是依赖 beanName 之间的映射关系：beanName - > 依赖 beanName 的集合。
	 * 指定 bean 与依赖指定 bean的集合，比如 bcd 依赖a，那么就是 key为 a，bcd为value
	 *
	 * 获取depends-on的属性值，非依赖注入的
	 *
	 */
	private final Map<String, Set<String>> dependentBeanMap = new ConcurrentHashMap<>(64);

	/** Map between depending bean names: bean name --> Set of bean names for the bean's dependencies */
	/**
	 * 指定bean与指定bean依赖的集合，比如a依赖bcd，那么就是key为a，bcd为value
	 */
	private final Map<String, Set<String>> dependenciesForBeanMap = new ConcurrentHashMap<>(64);


	@Override
	public void registerSingleton(String beanName, Object singletonObject) throws IllegalStateException {
		Assert.notNull(beanName, "Bean name must not be null");
		Assert.notNull(singletonObject, "Singleton object must not be null");
		synchronized (this.singletonObjects) {
			Object oldObject = this.singletonObjects.get(beanName);
			if (oldObject != null) {
				throw new IllegalStateException("Could not register object [" + singletonObject +
						"] under bean name '" + beanName + "': there is already object [" + oldObject + "] bound");
			}
			addSingleton(beanName, singletonObject);
		}
	}

	/**
	 * Add the given singleton object to the singleton cache of this factory.
	 * <p>To be called for eager registration of singletons.
	 * @param beanName the name of the bean
	 * @param singletonObject the singleton object
	 */
	protected void addSingleton(String beanName, Object singletonObject) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.put(beanName, singletonObject);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.add(beanName);
		}
	}

	/**
	 * Add the given singleton factory for building the specified singleton
	 * if necessary.
	 * <p>To be called for eager registration of singletons, e.g. to be able to
	 * resolve circular references.
	 * @param beanName the name of the bean
	 * @param singletonFactory the factory for the singleton object
	 */
	protected void addSingletonFactory(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(singletonFactory, "Singleton factory must not be null");
		synchronized (this.singletonObjects) {
			if (!this.singletonObjects.containsKey(beanName)) {
				this.singletonFactories.put(beanName, singletonFactory);
				this.earlySingletonObjects.remove(beanName);
				this.registeredSingletons.add(beanName);
			}
		}
	}

	@Override
	@Nullable
	public Object getSingleton(String beanName) {
		return getSingleton(beanName, true);
	}

	/**
	 * Return the (raw) singleton object registered under the given name.
	 * <p>Checks already instantiated singletons and also allows for an early
	 * reference to a currently created singleton (resolving a circular reference).
	 * @param beanName the name of the bean to look for
	 * @param allowEarlyReference whether early references should be created or not
	 * @return the registered singleton object, or {@code null} if none found
	 */
	@Nullable
	protected Object getSingleton(String beanName, boolean allowEarlyReference) {

		// allowEarlyReference 是否允许早期加载，默认的 getSingleton(String beanName) 内部调用时，传入的是 true
		// 从单例中获取缓存的 bean ，存在直接返回
		Object singletonObject = this.singletonObjects.get(beanName);
		// 如果已经实例好的 singletonObject 缓存中不存在， 并且正在创建中
		// 这里是两个判断条件。
		// 1、缓存中没有
		// 2、当前 beanName 是正在创建中的
		if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
			// 锁住 singletonObjects
			synchronized (this.singletonObjects) {
				// 从 earlySingletonObjects 中获取，此时是未全部实例化的，DI 尚未完成
				// 此时如果拿到值，说明是未全部进行初始化的。 这里也是 循环依赖的其中一处缓存
				singletonObject = this.earlySingletonObjects.get(beanName);

				// 还是么有找到，而且此时是允许早期加载的
				if (singletonObject == null && allowEarlyReference) {
					// 从 singletonFactories 中获取  ，又是一个 循环依赖的其中一处缓存
					ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);

					// 这里还有一个重要条件，就是 创建单例的 beanFactory 已经被创建了 （singletonFactory），而它是在 createBean 中被添加到集合的
					if (singletonFactory != null) {
						// 找到 创建 beanName 的工厂 singletonFactory
						singletonObject = singletonFactory.getObject();
						// 添加到 earlySingletonObjects
						// singletonFactories
						this.earlySingletonObjects.put(beanName, singletonObject);
						this.singletonFactories.remove(beanName);
					}
				}
			}
		}


		// 因此整体浏览下来我们就可以知道，从该方法获取到的 bean 可能是一个未完成初始化的bean，DI、后置处理器的调用等

		return singletonObject;
	}

	/**
	 * Return the (raw) singleton object registered under the given name,
	 * creating and registering a new one if none registered yet.
	 * @param beanName the name of the bean
	 * @param singletonFactory the ObjectFactory to lazily create the singleton
	 * with, if necessary
	 * @return the registered singleton object
	 */
	public Object getSingleton(String beanName, ObjectFactory<?> singletonFactory) {
		Assert.notNull(beanName, "Bean name must not be null");
		// 加全局锁
		synchronized (this.singletonObjects) {
			// 单例 bean 对象是否存在，存在直接返回了。
			// 因为 singleton 模式其实就是复用已经创建的 bean 所以这步骤必须检查
			Object singletonObject = this.singletonObjects.get(beanName);
			if (singletonObject == null) {

				// 缓存中没有找到，这里是真正创建 bean 过程开始了

				// 在当前的 beanFactory 环境中，单例 bean 是否正在被销毁bean的过程中. beanFactory 是否真正被销毁（关闭 beanFactory ，刷新 beanFactory 等）
				if (this.singletonsCurrentlyInDestruction) {
					throw new BeanCreationNotAllowedException(beanName,
							"Singleton bean creation not allowed while singletons of this factory are in destruction " +
							"(Do not request a bean from a BeanFactory in a destroy method implementation!)");
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Creating shared instance of singleton bean '" + beanName + "'");
				}

				// 开始创建单例 bean 之前， 将当前单例 beanName 存入到 singletonsCurrentlyInCreation ，表示当前单例 bean 正在被创建
				beforeSingletonCreation(beanName);

				// 标志位
				boolean newSingleton = false;
				// 异常栈标志
				boolean recordSuppressedExceptions = (this.suppressedExceptions == null);
				if (recordSuppressedExceptions) {
					this.suppressedExceptions = new LinkedHashSet<>();
				}
				try {
					// 调用 匿名内部类 createBean 方法
					singletonObject = singletonFactory.getObject();
					// 返回成功
					newSingleton = true;
				}
				catch (IllegalStateException ex) {
					// Has the singleton object implicitly appeared in the meantime ->
					// if yes, proceed with it since the exception indicates that state.
					singletonObject = this.singletonObjects.get(beanName);
					if (singletonObject == null) {
						// 抛出异常
						throw ex;
					}
				}
				catch (BeanCreationException ex) {
					if (recordSuppressedExceptions) {
						for (Exception suppressedException : this.suppressedExceptions) {
							// 添加异常栈
							ex.addRelatedCause(suppressedException);
						}
					}
					throw ex;
				}
				finally {
					// 释放异常栈 -- 暂时不明白这个异常暂的设计。 不妨碍主题
					if (recordSuppressedExceptions) {
						this.suppressedExceptions = null;
					}
					// bean 创建之后，主要是从 singletonsCurrentlyInCreation 中 清楚，表示 已经创建完成了。而不是正在被创建中
					afterSingletonCreation(beanName);
				}
				if (newSingleton) {
					// 1、首要任务是要添加到 singletonObjects 中，表明该单例 bean 已经创建成功了（包括属性注入、di 等操作，是一个可以直接使用的 bean 了）
					// 2、singletonFactories 要删除， 这个 singletonFactories 缓存创建该 bean 的 singletonFactory（即在 createBean 中可能将 singletonFactory 添加到 singletonFactories）
					// 3、earlySingletonObjects 要删除
					/**
					 * {@link DefaultSingletonBeanRegistry#getSingleton(String, boolean)} 中，如果 singletonObjects 中没有，且 singletonsCurrentlyInCreation 存在，
					 * 则会进行 earlySingletonObjects 添加
					 */
					// 4、registeredSingletons 要添加。记录单例 bean 创建的顺序


					// this.singletonObjects.put(beanName, singletonObject);
					// this.singletonFactories.remove(beanName);
					// this.earlySingletonObjects.remove(beanName);
					// this.registeredSingletons.add(beanName);
					addSingleton(beanName, singletonObject);
				}
			}
			return singletonObject;
		}
	}

	/**
	 * Register an Exception that happened to get suppressed during the creation of a
	 * singleton bean instance, e.g. a temporary circular reference resolution problem.
	 * @param ex the Exception to register
	 */
	protected void onSuppressedException(Exception ex) {
		synchronized (this.singletonObjects) {
			if (this.suppressedExceptions != null) {
				this.suppressedExceptions.add(ex);
			}
		}
	}

	/**
	 * Remove the bean with the given name from the singleton cache of this factory,
	 * to be able to clean up eager registration of a singleton if creation failed.
	 * @param beanName the name of the bean
	 * @see #getSingletonMutex()
	 */
	protected void removeSingleton(String beanName) {
		synchronized (this.singletonObjects) {
			this.singletonObjects.remove(beanName);
			this.singletonFactories.remove(beanName);
			this.earlySingletonObjects.remove(beanName);
			this.registeredSingletons.remove(beanName);
		}
	}

	@Override
	public boolean containsSingleton(String beanName) {
		return this.singletonObjects.containsKey(beanName);
	}

	@Override
	public String[] getSingletonNames() {
		synchronized (this.singletonObjects) {
			return StringUtils.toStringArray(this.registeredSingletons);
		}
	}

	@Override
	public int getSingletonCount() {
		synchronized (this.singletonObjects) {
			return this.registeredSingletons.size();
		}
	}


	public void setCurrentlyInCreation(String beanName, boolean inCreation) {
		Assert.notNull(beanName, "Bean name must not be null");
		if (!inCreation) {
			this.inCreationCheckExclusions.add(beanName);
		}
		else {
			this.inCreationCheckExclusions.remove(beanName);
		}
	}

	public boolean isCurrentlyInCreation(String beanName) {
		Assert.notNull(beanName, "Bean name must not be null");
		return (!this.inCreationCheckExclusions.contains(beanName) && isActuallyInCreation(beanName));
	}

	protected boolean isActuallyInCreation(String beanName) {
		return isSingletonCurrentlyInCreation(beanName);
	}

	/**
	 * Return whether the specified singleton bean is currently in creation
	 *
	 * 返回指定的单例bean当前是否正在创建中
	 *
	 * (within the entire factory).
	 * @param beanName the name of the bean
	 */
	public boolean isSingletonCurrentlyInCreation(String beanName) {
		return this.singletonsCurrentlyInCreation.contains(beanName);
	}

	/**
	 * Callback before singleton creation.
	 * <p>The default implementation register the singleton as currently in creation.
	 * @param beanName the name of the singleton about to be created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void beforeSingletonCreation(String beanName) {
		// inCreationCheckExclusions： 当前在创建检查中排除的bean名称
		// 单例bean 创建之前的排除。满足以下两个条件
		// 1、beanName 不是被排除的
		// 2、将当前的 beanName 添加到 singletonsCurrentlyInCreation 中（如果已经存在，返回 false）
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.add(beanName)) {
			throw new BeanCurrentlyInCreationException(beanName);
		}
	}

	/**
	 * Callback after singleton creation.
	 * <p>The default implementation marks the singleton as not in creation anymore.
	 * @param beanName the name of the singleton that has been created
	 * @see #isSingletonCurrentlyInCreation
	 */
	protected void afterSingletonCreation(String beanName) {
		if (!this.inCreationCheckExclusions.contains(beanName) && !this.singletonsCurrentlyInCreation.remove(beanName)) {
			throw new IllegalStateException("Singleton '" + beanName + "' isn't currently in creation");
		}
	}


	/**
	 * Add the given bean to the list of disposable beans in this registry.
	 * <p>Disposable beans usually correspond to registered singletons,
	 * matching the bean name but potentially being a different instance
	 * (for example, a DisposableBean adapter for a singleton that does not
	 * naturally implement Spring's DisposableBean interface).
	 * @param beanName the name of the bean
	 * @param bean the bean instance
	 */
	public void registerDisposableBean(String beanName, DisposableBean bean) {
		synchronized (this.disposableBeans) {
			this.disposableBeans.put(beanName, bean);
		}
	}

	/**
	 * Register a containment relationship between two beans,
	 * e.g. between an inner bean and its containing outer bean.
	 * <p>Also registers the containing bean as dependent on the contained bean
	 * in terms of destruction order.
	 * @param containedBeanName the name of the contained (inner) bean
	 * @param containingBeanName the name of the containing (outer) bean
	 * @see #registerDependentBean
	 */
	public void registerContainedBean(String containedBeanName, String containingBeanName) {
		synchronized (this.containedBeanMap) {
			Set<String> containedBeans =
					this.containedBeanMap.computeIfAbsent(containingBeanName, k -> new LinkedHashSet<>(8));
			if (!containedBeans.add(containedBeanName)) {
				return;
			}
		}
		registerDependentBean(containedBeanName, containingBeanName);
	}

	/**
	 * 为给定的 bean 注册一个依赖的 bean，在销毁给定的bean之前将其销毁
	 * Register a dependent bean for the given bean,
	 * to be destroyed before the given bean is destroyed.
	 * @param beanName the name of the bean
	 * @param dependentBeanName the name of the dependent bean
	 */
	public void registerDependentBean(String beanName, String dependentBeanName) {
		// 获取原始名字
		String canonicalName = canonicalName(beanName);

		// 全局锁
		// dependentBeanMap ： key 对应的bean 都被 value 对应的 集合里边的每一个 bean 依赖了
		synchronized (this.dependentBeanMap) {
			Set<String> dependentBeans =
					this.dependentBeanMap.computeIfAbsent(canonicalName, k -> new LinkedHashSet<>(8));
			// 如果 dependentBeanName 已经存在，说明添加过了，直接返回了
			if (!dependentBeans.add(dependentBeanName)) {
				return;
			}
		}

		// 结合 dependentBeans 中之前没有存在 dependentBeanName，则需要多一步操作
		// dependenciesForBeanMap : key 表示的bean 都 依赖了哪些 （values）beans 集合
		synchronized (this.dependenciesForBeanMap) {
			// 这个 dependenciesForBeanMap 存放的是 写了 depends-on 的 bean 一 depends-on 属性value 的 bean 的映射。与 dependentBeanMap 刚好相反
			Set<String> dependenciesForBean =
					this.dependenciesForBeanMap.computeIfAbsent(dependentBeanName, k -> new LinkedHashSet<>(8));
			dependenciesForBean.add(canonicalName);
		}
	}

	/**
	 *
	 *  dependentBeanName 是否依赖 beanName
	 *	dependentBeanMap 存放的是：指定 bean 与依赖指定 bean的集合，比如 bcd 依赖 a，那么就是 key为 a，bcd为value
	 *
	 * Determine whether the specified dependent bean has been registered as
	 * dependent on the given bean or on any of its transitive dependencies.
	 * @param beanName the name of the bean to check
	 * @param dependentBeanName the name of the dependent bean
	 * @since 4.0
	 */
	protected boolean isDependent(String beanName, String dependentBeanName) {
		// 锁住依赖 map
		synchronized (this.dependentBeanMap) {
			return isDependent(beanName, dependentBeanName, null);
		}
	}

	/**
	 * dependentBeanName 是否依赖 beanName
	 *
	 *  == dependentBeanMap.get(beanA)
	 * 1、如果此时为空，则表示当前的 beanA 没有被任何的 bean 所依赖，返回就行了
	 * 2、如果此时不为空
	 *    1、依赖 beanA 的 bean 集合中包含了 beanB，则说明存在循环依赖了。即 A 依赖 B ，同时 B 依赖 A，直接返回 true 了
	 *    2、依赖 beanA 的集合 bean 中不包含 beanB 。但是包含了 beanC、beanD --  isDependent(beanC，beanB, alreadyScreen(A))
	 * 	1、先记录下 beanA 是已经扫描过的
	 *                 2、循环变量 beanC、beanD ，比较 beanB 是否依赖了 beanC、beanD，注意此时传入了 一个集合，这个集合包含了 beanA（alreadyScreen）
	 *                 3、如果当前 的 alreadyScreen 包含了 isDependent()方法的第一个参数（比如此时为 beanA），说明重复对比了，也就是没有循环依赖，返回 false
	 *                 4、如果3没有成立
	 * 	      则会记录走这上面的逻辑，找到所有 依赖 beanC 的对象，比较是否包含 beanB
	 *                       1、如果 beanC、beanD 其中某个 被 beanB 所依赖，则返回 true，说明 beanB 依赖 beanC，而 beanC 又依赖 beanA，传递依赖
	 *                       2、如果 1 没成立，则返回 false
	 *                （此时可能还会比较找到所有 依赖 beanD 、beanC的所有 bean，再核查这些 beans 是否被 beanB 所依赖，即可能存在多层级的循环依赖）
	 * -- 这是一段比较绕的逻辑
	 *
	 *
	 * @param beanName
	 * @param dependentBeanName
	 * @param alreadySeen
	 * @return
	 */
	private boolean isDependent(String beanName, String dependentBeanName, @Nullable Set<String> alreadySeen) {
		// alreadySeen 已经检测的依赖 bean
		if (alreadySeen != null && alreadySeen.contains(beanName)) {
			return false;
		}
		// 获取原始名 -- 可能是别名
		String canonicalName = canonicalName(beanName);
		// 为空，说明没有任何的 bean 依赖beanName，直接返回false
		Set<String> dependentBeans = this.dependentBeanMap.get(canonicalName);
		if (dependentBeans == null) {
			return false;
		}
		// 有其他bean依赖beanName，且包含了dependentBeanName，返回true
		if (dependentBeans.contains(dependentBeanName)) {
			return true;
		}
		// 有其他bean依赖beanName，但是不包含 dependentBeanName
		// 遍历其他的 依赖 beanName 的 bean ，看看是否 dependentBeanName 依赖了这些 bean
		for (String transitiveDependency : dependentBeans) {
			if (alreadySeen == null) {
				alreadySeen = new HashSet<>();
			}
			// 是否有循环依赖
			alreadySeen.add(beanName);
			if (isDependent(transitiveDependency, dependentBeanName, alreadySeen)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Determine whether a dependent bean has been registered for the given name.
	 * @param beanName the name of the bean to check
	 */
	protected boolean hasDependentBean(String beanName) {
		return this.dependentBeanMap.containsKey(beanName);
	}

	/**
	 * Return the names of all beans which depend on the specified bean, if any.
	 * @param beanName the name of the bean
	 * @return the array of dependent bean names, or an empty array if none
	 */
	public String[] getDependentBeans(String beanName) {
		Set<String> dependentBeans = this.dependentBeanMap.get(beanName);
		if (dependentBeans == null) {
			return new String[0];
		}
		synchronized (this.dependentBeanMap) {
			return StringUtils.toStringArray(dependentBeans);
		}
	}

	/**
	 * Return the names of all beans that the specified bean depends on, if any.
	 * @param beanName the name of the bean
	 * @return the array of names of beans which the bean depends on,
	 * or an empty array if none
	 */
	public String[] getDependenciesForBean(String beanName) {
		Set<String> dependenciesForBean = this.dependenciesForBeanMap.get(beanName);
		if (dependenciesForBean == null) {
			return new String[0];
		}
		synchronized (this.dependenciesForBeanMap) {
			return StringUtils.toStringArray(dependenciesForBean);
		}
	}

	public void destroySingletons() {
		if (logger.isDebugEnabled()) {
			logger.debug("Destroying singletons in " + this);
		}
		synchronized (this.singletonObjects) {
			// 单例Bean 正在被销毁标志
			this.singletonsCurrentlyInDestruction = true;
		}

		String[] disposableBeanNames;
		synchronized (this.disposableBeans) {
			// 获取到 disposable 销毁方法的Bean
			disposableBeanNames = StringUtils.toStringArray(this.disposableBeans.keySet());
		}
		for (int i = disposableBeanNames.length - 1; i >= 0; i--) {
			// 调用销毁的方法
			destroySingleton(disposableBeanNames[i]);
		}

		this.containedBeanMap.clear();
		this.dependentBeanMap.clear();
		this.dependenciesForBeanMap.clear();

		clearSingletonCache();
	}

	/**
	 * Clear all cached singleton instances in this registry.
	 * @since 4.3.15
	 */
	protected void clearSingletonCache() {
		synchronized (this.singletonObjects) {
			this.singletonObjects.clear();
			this.singletonFactories.clear();
			this.earlySingletonObjects.clear();
			this.registeredSingletons.clear();
			this.singletonsCurrentlyInDestruction = false;
		}
	}

	/**
	 * Destroy the given bean. Delegates to {@code destroyBean}
	 * if a corresponding disposable bean instance is found.
	 * @param beanName the name of the bean
	 * @see #destroyBean
	 */
	public void destroySingleton(String beanName) {
		// Remove a registered singleton of the given name, if any.
		// 如果存在单例对象，删除
		// 删除单例Object、注册的单例Bean、单例Bean 工厂、延迟Bean
		removeSingleton(beanName);

		// Destroy the corresponding DisposableBean instance.
		// 委托给 destroyBean 进行销毁
		DisposableBean disposableBean;
		synchronized (this.disposableBeans) {
			disposableBean = (DisposableBean) this.disposableBeans.remove(beanName);
		}
		// Bean 销毁的逻辑
		destroyBean(beanName, disposableBean);
	}

	/**
	 * 在自己销毁之前必须先销毁其依赖的bean
	 * -- ？ 循环依赖？，不会出现循环依赖的问题吗？ 不会，在第二个循环时，缓存中的BeanName 已经被remove 了
	 * Destroy the given bean. Must destroy beans that depend on the given
	 * bean before the bean itself. Should not throw any exceptions.
	 * @param beanName the name of the bean
	 * @param bean the bean instance to destroy
	 */
	protected void destroyBean(String beanName, @Nullable DisposableBean bean) {
		// Trigger destruction of dependent beans first...
		// 首先触发依赖的Bean 的销毁动作
		Set<String> dependencies;
		// dependentBeanMap 中可以找到
		synchronized (this.dependentBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			dependencies = this.dependentBeanMap.remove(beanName);
		}
		if (dependencies != null) {
			if (logger.isDebugEnabled()) {
				logger.debug("Retrieved dependent beans for bean '" + beanName + "': " + dependencies);
			}
			for (String dependentBeanName : dependencies) {
				// 销毁依赖的单例Bean
				destroySingleton(dependentBeanName);
			}
		}

		// Actually destroy the bean now...
		if (bean != null) {
			try {
				// org.springframework.beans.factory.support.DisposableBeanAdapter#destroy
				bean.destroy();
			}
			catch (Throwable ex) {
				// 捕获但是不抛出异常
				logger.error("Destroy method on bean with name '" + beanName + "' threw an exception", ex);
			}
		}

		// Trigger destruction of contained beans...
		/**
		 * Bean 包含的 Bean 集合  beanName -> Set<BeanName>
		 */
		Set<String> containedBeans;
		synchronized (this.containedBeanMap) {
			// Within full synchronization in order to guarantee a disconnected Set
			containedBeans = this.containedBeanMap.remove(beanName);
		}
		if (containedBeans != null) {
			for (String containedBeanName : containedBeans) {
				destroySingleton(containedBeanName);
			}
		}

		// Remove destroyed bean from other beans' dependencies.
		synchronized (this.dependentBeanMap) {
			for (Iterator<Map.Entry<String, Set<String>>> it = this.dependentBeanMap.entrySet().iterator(); it.hasNext();) {
				Map.Entry<String, Set<String>> entry = it.next();
				Set<String> dependenciesToClean = entry.getValue();
				dependenciesToClean.remove(beanName);
				if (dependenciesToClean.isEmpty()) {
					it.remove();
				}
			}
		}

		// Remove destroyed bean's prepared dependency information.
		this.dependenciesForBeanMap.remove(beanName);
	}

	/**
	 * Exposes the singleton mutex to subclasses and external collaborators.
	 * <p>Subclasses should synchronize on the given Object if they perform
	 * any sort of extended singleton creation phase. In particular, subclasses
	 * should <i>not</i> have their own mutexes involved in singleton creation,
	 * to avoid the potential for deadlocks in lazy-init situations.
	 */
	public final Object getSingletonMutex() {
		return this.singletonObjects;
	}

}
