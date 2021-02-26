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

package org.springframework.beans.factory.xml;

import java.io.IOException;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import org.springframework.beans.factory.BeanDefinitionStoreException;
import org.springframework.beans.factory.config.BeanDefinitionHolder;
import org.springframework.beans.factory.parsing.BeanComponentDefinition;
import org.springframework.beans.factory.support.BeanDefinitionReaderUtils;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternUtils;
import org.springframework.lang.Nullable;
import org.springframework.util.Assert;
import org.springframework.util.ResourceUtils;
import org.springframework.util.StringUtils;

/**
 * Default implementation of the {@link BeanDefinitionDocumentReader} interface that
 * reads bean definitions according to the "spring-beans" DTD and XSD format
 * (Spring's default XML bean definition format).
 *
 * <p>The structure, elements, and attribute names of the required XML document
 * are hard-coded in this class. (Of course a transform could be run if necessary
 * to produce this format). {@code <beans>} does not need to be the root
 * element of the XML document: this class will parse all bean definition elements
 * in the XML file, regardless of the actual root element.
 *
 * @author Rod Johnson
 * @author Juergen Hoeller
 * @author Rob Harrop
 * @author Erik Wiersma
 * @since 18.12.2003
 */
public class DefaultBeanDefinitionDocumentReader implements BeanDefinitionDocumentReader {

	public static final String BEAN_ELEMENT = BeanDefinitionParserDelegate.BEAN_ELEMENT;

	public static final String NESTED_BEANS_ELEMENT = "beans";

	public static final String ALIAS_ELEMENT = "alias";

	public static final String NAME_ATTRIBUTE = "name";

	public static final String ALIAS_ATTRIBUTE = "alias";

	public static final String IMPORT_ELEMENT = "import";

	public static final String RESOURCE_ATTRIBUTE = "resource";

	public static final String PROFILE_ATTRIBUTE = "profile";


	protected final Log logger = LogFactory.getLog(getClass());

	@Nullable
	private XmlReaderContext readerContext;

	@Nullable
	private BeanDefinitionParserDelegate delegate;


	/**
	 * This implementation parses bean definitions according to the "spring-beans" XSD
	 * (or DTD, historically).
	 * <p>Opens a DOM Document; then initializes the default settings
	 * specified at the {@code <beans/>} level; then parses the contained bean definitions.
	 */
	@Override
	public void registerBeanDefinitions(Document doc, XmlReaderContext readerContext) {
		// 注意，这里的 readerContext 持有 XmlBeanDefinitionReader, 它又持有 BeanDefinitionRegistry ，而 registry 本身剧本beanDefinition 注册功能
		this.readerContext = readerContext;
		logger.debug("Loading bean definitions");
		// 获取XML Root 节点
		Element root = doc.getDocumentElement();
		// 从 Root 开始自上而下进行解析
		doRegisterBeanDefinitions(root);
	}

	/**
	 * Return the descriptor for the XML resource that this parser works on.
	 */
	protected final XmlReaderContext getReaderContext() {
		Assert.state(this.readerContext != null, "No XmlReaderContext available");
		return this.readerContext;
	}

	/**
	 * Invoke the {@link org.springframework.beans.factory.parsing.SourceExtractor}
	 * to pull the source metadata from the supplied {@link Element}.
	 */
	@Nullable
	protected Object extractSource(Element ele) {
		return getReaderContext().extractSource(ele);
	}


	/**
	 * Register each bean definition within the given root {@code <beans/>} element.
	 */
	protected void doRegisterBeanDefinitions(Element root) {
		// Any nested <beans> elements will cause recursion in this method. In
		// order to propagate and preserve <beans> default-* attributes correctly,
		// keep track of the current (parent) delegate, which may be null. Create
		// the new (child) delegate with a reference to the parent for fallback purposes,
		// then ultimately reset this.delegate back to its original (parent) reference.
		// this behavior emulates a stack of delegates without actually necessitating one.

		// 解析每一个 Bean 被委托到 BeanDefinitionParserDelegate -- // 父对象
		// 首先记录上一个 BeanDefinitionParserDelegate 对象
		BeanDefinitionParserDelegate parent = this.delegate;
		// 创建一个新的 ParseDelegate 解析类，并且做了一些动作，会尝试与 存在子父 parent 关系的 init-method 等更新到 delegate 的默认值
		// 这段是一个难点
		// BeanDefinitionParserDelegate 是一个重要的类， 负责解析 BeanDefinition
		// 传递 父类，是因为 有些默认属性，可以继承父类由来
		this.delegate = createDelegate(getReaderContext(), root, parent);

		// 进行格式校验
		// 检查 <beans /> 根标签的命名空间是否为空，或者是 http://www.springframework.org/schema/beans
		// 默认命名空间为 bean 标签，alias 标签 和 import 标签，此处为 bean 标签

		// bean 标签的 xsd 定义。 targetNamespace 命名标签
		// <xsd:schema xmlns="http://www.springframework.org/schema/beans"
		//		xmlns:xsd="http://www.w3.org/2001/XMLSchema"
		//		targetNamespace="http://www.springframework.org/schema/beans">
		if (this.delegate.isDefaultNamespace(root)) {
			// 获取 beans 标签的 profile 属性
			String profileSpec = root.getAttribute(PROFILE_ATTRIBUTE);
			if (StringUtils.hasText(profileSpec)) {
				// ,; , || ; || ,; 分隔符切割
				String[] specifiedProfiles = StringUtils.tokenizeToStringArray(
						profileSpec, BeanDefinitionParserDelegate.MULTI_VALUE_ATTRIBUTE_DELIMITERS);
				// 如果所有 profile 都无效，则不进行注册
				// 这里还会根据 spring.profiles.active 即 {profile} 来过滤判断
				// 此处会对 specifiedProfiles 文件格式进行校验，并且会结合 Environment（又包括有 profile 激活的环境）
				if (!getReaderContext().getEnvironment().acceptsProfiles(specifiedProfiles)) {
					if (logger.isInfoEnabled()) {
						logger.info("Skipped XML bean definition file due to specified profiles [" + profileSpec +
								"] not matching: " + getReaderContext().getResource());
					}
					return;
				}
			}
		}

		// 扩展点之一吧，XML BeanDefinition 解析之前
		preProcessXml(root);
		parseBeanDefinitions(root, this.delegate);
		// 扩展点之一吧，XML BeanDefinition 解析之后
		postProcessXml(root);

		// 下一次循环时的父节点
		this.delegate = parent;
	}

	protected BeanDefinitionParserDelegate createDelegate(
			XmlReaderContext readerContext, Element root, @Nullable BeanDefinitionParserDelegate parentDelegate) {

		BeanDefinitionParserDelegate delegate = new BeanDefinitionParserDelegate(readerContext);
		// 初始化 默认值
		delegate.initDefaults(root, parentDelegate);
		return delegate;
	}

	/**
	 * 解析 bean import alias bean 等
	 *
	 *     <!--别名,用别名也可以实现类的调用-->
	 *     <alias name="user" alias="newUser"/>
	 *
	 *     <!--id,唯一标识符， class全限定名，包名+类名， name，别名而且比alias更高级，可以一对多-->
	 *     <bean id="user" class="com.king.pojo.User" name="user2,u2">
	 *         <property name="name" value="king"/>
	 *     </bean>
	 *
	 * 		<!-- import 标签-->
	 *     <import resource="beans.xml"/>
	 *     <import resource="beans2.xml"/>
	 *     <import resource="beans3.xml"/>
	 *
	 *
	 * Parse the elements at the root level in the document:
	 * "import", "alias", "bean".
	 * @param root the DOM root element of the document
	 */
	protected void parseBeanDefinitions(Element root, BeanDefinitionParserDelegate delegate) {
		// 如果是默认的命名空间，则由spring 默认的去解析
		if (delegate.isDefaultNamespace(root)) {
			// 得到子标签 beans 就是每个 bean 、import、alias 等
			NodeList nl = root.getChildNodes();
			for (int i = 0; i < nl.getLength(); i++) {
				Node node = nl.item(i);
				// 是一个 Element 元素，而不是 text 文本，属性?
				if (node instanceof Element) {
					Element ele = (Element) node;
					// 是默认的 命名空间中的
					if (delegate.isDefaultNamespace(ele)) {
						// 解析spring 默认的元素
						parseDefaultElement(ele, delegate);
					}
					else {
						delegate.parseCustomElement(ele);
					}
				}
			}
		}
		else {
			// 否则交给扩展类去解析--其实就是自定义标签，比如注解开关，spring-mvc 开关等
			// 比如 dubbo 自定义的标签，事务标签等
			delegate.parseCustomElement(root);
		}
	}

	private void parseDefaultElement(Element ele, BeanDefinitionParserDelegate delegate) {
		// 如果是 import，采用 import 把过多的 bean 定义放到别的地方，使得更加容易维护
		if (delegate.nodeNameEquals(ele, IMPORT_ELEMENT)) {
			// 找到 resource 标签，并且尝试解析占位符 ${} ， 判定是绝对路径还是相对路径
			// 根据绝对路径和相对路径的方式获取 文件流，然后重新调用 loadBeanDefinitions
			importBeanDefinitionResource(ele);
		}
		// 如果是 alias
		else if (delegate.nodeNameEquals(ele, ALIAS_ELEMENT)) {
			// 处理别名
			processAliasRegistration(ele);
		}
		// 如果是 bean
		else if (delegate.nodeNameEquals(ele, BEAN_ELEMENT)) {
			// 处理Bean 成为 BeanDefinition
			processBeanDefinition(ele, delegate);
		}
		// 如果是 beans
		else if (delegate.nodeNameEquals(ele, NESTED_BEANS_ELEMENT)) {
			// recurse
			doRegisterBeanDefinitions(ele);
		}
	}

	/**
	 * Parse an "import" element and load the bean definitions
	 * from the given resource into the bean factory.
	 */
	protected void importBeanDefinitionResource(Element ele) {
		// 获取 resource ，得到 引入的 XML 的路径
		String location = ele.getAttribute(RESOURCE_ATTRIBUTE);
		if (!StringUtils.hasText(location)) {
			getReaderContext().error("Resource location must not be empty", ele);
			return;
		}

		// Resolve system properties: e.g. "${user.dir}"
		// 解析占位符，这个占位符如何解析的？什么时候初始化好的？ 待解答，探究
		// 这里是能解析系统级别的吗？ 用户自定义的在哪里解析，是否可以解析？？？
		location = getReaderContext().getEnvironment().resolveRequiredPlaceholders(location);

		// 存放 import 解析到的导入文件的额外的文件 Resource
		Set<Resource> actualResources = new LinkedHashSet<>(4);

		// Discover whether the location is an absolute or relative URI
		// location 是否是绝对路径
		boolean absoluteLocation = false;
		try {
			// 认为 classpath*: 或者 classpath: 开头的为绝对路径
			// 认为能够通过该 location 构建出 java.net.URL 为绝对路径
			// 根据 location 构造 java.net.URI 判断调用 #isAbsolute() 方法，判断是否为绝对路径
			absoluteLocation = ResourcePatternUtils.isUrl(location) || ResourceUtils.toURI(location).isAbsolute();
		}
		catch (URISyntaxException ex) {
			// cannot convert to an URI, considering the location relative
			// unless it is the well-known Spring prefix "classpath*:"

			// 不能够 解析为一个 URI
			// 除非它是众所周知的Spring前缀“classpath*:”
		}

		// Absolute or relative?
		// 绝对路径的分支
		if (absoluteLocation) {
			try {
				// 调用 XmlBeanDefinitionReader#loadBeanDefinitions
				int importCount = getReaderContext().getReader().loadBeanDefinitions(location, actualResources);
				if (logger.isDebugEnabled()) {
					logger.debug("Imported " + importCount + " bean definitions from URL location [" + location + "]");
				}
			}
			catch (BeanDefinitionStoreException ex) {
				// 拿到错误信息
				getReaderContext().error(
						"Failed to import bean definitions from URL location [" + location + "]", ele, ex);
			}
		}
		else {
			// No URL -> considering resource location as relative to the current file.
			// 考虑相对于当前文件的资源位置，相对路径
			// 首先会根据 当前的 Resource 构建出相对路径。 之前学习 Resource 的体系了解到了，根据根据当前 Resource ，传入相对路径
			// 读取到 相对路径文件的 Resource（可能，如果相对路径正确或者存在的话）
			try {
				int importCount;
				// 创建一个相对路径的 Resource 资源
				Resource relativeResource = getReaderContext().getResource().createRelative(location);
				// 相对路径资源存在
				if (relativeResource.exists()) {
					// 解析相对路径资源
					importCount = getReaderContext().getReader().loadBeanDefinitions(relativeResource);
					actualResources.add(relativeResource);
				}
				// 不存在
				else {
					// 获得根路经地址（当前 Resource 的地址 ，即拥有 import 标签的 Resource ）
					String baseLocation = getReaderContext().getResource().getURL().toString();
					// 根据当前的 baseLocation 构造一个绝对路径
					importCount = getReaderContext().getReader().loadBeanDefinitions(
							StringUtils.applyRelativePath(baseLocation, location), actualResources);
				}
				if (logger.isDebugEnabled()) {
					logger.debug("Imported " + importCount + " bean definitions from relative location [" + location + "]");
				}
			}
			catch (IOException ex) {
				getReaderContext().error("Failed to resolve current resource location", ele, ex);
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to import bean definitions from relative location [" + location + "]",
						ele, ex);
			}
		}
		// 额外的资源
		Resource[] actResArray = actualResources.toArray(new Resource[0]);
		// 发布一个Import 标签解析事件
		getReaderContext().fireImportProcessed(location, actResArray, extractSource(ele));
	}

	/**
	 * Process the given alias element, registering the alias with the registry.
	 */
	protected void processAliasRegistration(Element ele) {
		// alias - name 属性
		String name = ele.getAttribute(NAME_ATTRIBUTE);
		// alias - 别名是啥
		String alias = ele.getAttribute(ALIAS_ATTRIBUTE);
		boolean valid = true;
		if (!StringUtils.hasText(name)) {
			getReaderContext().error("Name must not be empty", ele);
			valid = false;
		}
		if (!StringUtils.hasText(alias)) {
			getReaderContext().error("Alias must not be empty", ele);
			valid = false;
		}
		// 校验成功
		if (valid) {
			try {
				// 通过一路传递的 readerContext 找到他持有的 registry ，进行一个别名的注册
				getReaderContext().getRegistry().registerAlias(name, alias);
			}
			catch (Exception ex) {
				getReaderContext().error("Failed to register alias '" + alias +
						"' for bean with name '" + name + "'", ele, ex);
			}
			// 发布一个别名注册事件--当然这里的Listener 默认实现中， do nothing
			getReaderContext().fireAliasRegistered(name, alias, extractSource(ele));
		}
	}

	/**
	 * Process the given bean element, parsing the bean definition
	 * and registering it with the registry.
	 */
	protected void processBeanDefinition(Element ele, BeanDefinitionParserDelegate delegate) {
		// 通过 delegate 生成一个 BeanDefinitionHolder
		BeanDefinitionHolder bdHolder = delegate.parseBeanDefinitionElement(ele);
		if (bdHolder != null) {
			// 修饰 BeanDefinitionHolder
			bdHolder = delegate.decorateBeanDefinitionIfRequired(ele, bdHolder);
			try {
				// Register the final decorated instance.
				// 使用BeanDefinitionReaderUtils 进行Bean 注册
				BeanDefinitionReaderUtils.registerBeanDefinition(bdHolder, getReaderContext().getRegistry());
			}
			catch (BeanDefinitionStoreException ex) {
				getReaderContext().error("Failed to register bean definition with name '" +
						bdHolder.getBeanName() + "'", ele, ex);
			}
			// Send registration event.
			// 发布一个组件（Bean）注册事件
			getReaderContext().fireComponentRegistered(new BeanComponentDefinition(bdHolder));
		}
	}


	/**
	 * Allow the XML to be extensible by processing any custom element types first,
	 * before we start to process the bean definitions. This method is a natural
	 * extension point for any other custom pre-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void preProcessXml(Element root) {
	}

	/**
	 * Allow the XML to be extensible by processing any custom element types last,
	 * after we finished processing the bean definitions. This method is a natural
	 * extension point for any other custom post-processing of the XML.
	 * <p>The default implementation is empty. Subclasses can override this method to
	 * convert custom elements into standard Spring bean definitions, for example.
	 * Implementors have access to the parser's bean definition reader and the
	 * underlying XML resource, through the corresponding accessors.
	 * @see #getReaderContext()
	 */
	protected void postProcessXml(Element root) {
	}

}
