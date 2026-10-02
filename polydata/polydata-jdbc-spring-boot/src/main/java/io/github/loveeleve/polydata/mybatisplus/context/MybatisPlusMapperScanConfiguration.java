/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.github.loveeleve.polydata.mybatisplus.context;

import com.baomidou.mybatisplus.autoconfigure.ConfigurationCustomizer;
import com.baomidou.mybatisplus.autoconfigure.MybatisPlusProperties;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.mapper.Mapper;
import com.baomidou.mybatisplus.core.toolkit.ExceptionUtils;
import com.baomidou.mybatisplus.core.toolkit.StringPool;
import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.type.EnumTypeHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.core.type.classreading.CachingMetadataReaderFactory;
import org.springframework.core.type.classreading.MetadataReader;
import org.springframework.core.type.classreading.MetadataReaderFactory;
import org.springframework.util.ClassUtils;

import java.util.HashSet;
import java.util.Set;

import static io.github.loveeleve.polydata.mybatisplus.constants.MybatisPlusConstants.DYNAMIC_JDBC_MYBATIS_PLUS_BASE_PACKAGES_PLACEHOLDER;
import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * The Configuration class to register the annotated {@link Mapper} interfaces under
 * {@link DynamicJdbcConfig.MybatisPlus#getBasePackages()}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@MapperScan(basePackages = DYNAMIC_JDBC_MYBATIS_PLUS_BASE_PACKAGES_PLACEHOLDER)
class MybatisPlusMapperScanConfiguration {

    private static final ResourcePatternResolver RESOURCE_PATTERN_RESOLVER = new PathMatchingResourcePatternResolver();

    private static final MetadataReaderFactory METADATA_READER_FACTORY = new CachingMetadataReaderFactory(RESOURCE_PATTERN_RESOLVER);

    private static final String MYBATIS_TYPE_HANDLERS_PACKAGE_VALUE = "io.github.loveeleve.polydata.mybatisplus.handle";

    @Bean
    public ConfigurationCustomizer configurationCustomizer(MybatisPlusProperties mybatisProperties, DynamicJdbcConfig dynamicJdbcConfig) {

        return new ConfigurationCustomizer() {

            @Override
            public void customize(MybatisConfiguration configuration) {
                String typeHandlersPackage = mybatisProperties.getTypeHandlersPackage();
                DynamicJdbcConfig.MybatisPlus mybatisPlus = dynamicJdbcConfig.getMybatisPlus();
                String typeEnumsPackage = mybatisPlus.getBasePackages();
                // If the type-handlers package of the architecture group contains extended type handlers
                if (StringUtils.isNotEmpty(typeHandlersPackage) && StringUtils.contains(typeHandlersPackage, MYBATIS_TYPE_HANDLERS_PACKAGE_VALUE)
                        && StringUtils.isNotEmpty(typeEnumsPackage)) {
                    Set<Class> classes;
                    if (typeEnumsPackage.contains(StringPool.STAR) && !typeEnumsPackage.contains(StringPool.COMMA)
                            && !typeEnumsPackage.contains(StringPool.SEMICOLON)) {
                        classes = scanTypePackage(typeEnumsPackage);
                    } else {
                        String[] typeHandlersPackageArray =
                                tokenizeToStringArray(typeEnumsPackage, ConfigurableApplicationContext.CONFIG_LOCATION_DELIMITERS);
                        classes = new HashSet<>();
                        for (String typePackage : typeHandlersPackageArray) {
                            Set<Class> scanTypePackage = scanTypePackage(typePackage);
                            if (scanTypePackage.isEmpty()) {
                            } else {
                                classes.addAll(scanTypePackage);
                            }
                        }
                    }
                    for (Class cls : classes) {
                        if (cls.isEnum()) {
                            configuration.getTypeHandlerRegistry().register(cls, EnumTypeHandler.class);
                        }
                    }
                }
            }

        };
    }

    private Set<Class> scanTypePackage(String typePackage) {
        String pkg = ResourcePatternResolver.CLASSPATH_ALL_URL_PREFIX + ClassUtils.convertClassNameToResourcePath(typePackage) + "/**/*.class";
        try {
            Set<Class> set = new HashSet<>();
            Resource[] resources = RESOURCE_PATTERN_RESOLVER.getResources(pkg);
            if (resources != null && resources.length > 0) {
                MetadataReader metadataReader;
                for (Resource resource : resources) {
                    if (resource.isReadable()) {
                        metadataReader = METADATA_READER_FACTORY.getMetadataReader(resource);
                        set.add(Class.forName(metadataReader.getClassMetadata().getClassName()));
                    }
                }
            }
            return set;
        } catch (Exception e) {
            throw ExceptionUtils.mpe("not find scanTypePackage: %s", e, pkg);
        }
    }
}
