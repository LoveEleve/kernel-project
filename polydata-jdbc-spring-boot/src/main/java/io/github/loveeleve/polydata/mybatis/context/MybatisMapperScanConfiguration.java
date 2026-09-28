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
package io.github.loveeleve.polydata.mybatis.context;

import io.github.loveeleve.polydata.config.DynamicJdbcConfig;
import io.github.loveeleve.polydata.mybatis.constants.MybatisConstants;
import org.apache.commons.lang3.StringUtils;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.type.EnumTypeHandler;
import org.mybatis.spring.annotation.MapperScan;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.mybatis.spring.boot.autoconfigure.MybatisProperties;
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

import static io.github.loveeleve.polydata.mybatis.constants.MybatisConstants.DYNAMIC_JDBC_MYBATIS_BASE_PACKAGES_PLACEHOLDER;
import static org.springframework.util.StringUtils.tokenizeToStringArray;

/**
 * The Configuration class to register the annotated {@link Mapper} interfaces under
 * {@link DynamicJdbcConfig.Mybatis#getBasePackages()}
 * 
 * @author <a href="https://github.com/LoveEleve">LoveEleve</a>
 * @since 1.0.0
 */
@MapperScan(basePackages = DYNAMIC_JDBC_MYBATIS_BASE_PACKAGES_PLACEHOLDER)
class MybatisMapperScanConfiguration {

    private static final ResourcePatternResolver RESOURCE_PATTERN_RESOLVER = new PathMatchingResourcePatternResolver();

    private static final MetadataReaderFactory METADATA_READER_FACTORY = new CachingMetadataReaderFactory(RESOURCE_PATTERN_RESOLVER);

    private static final String MYBATIS_TYPE_HANDLERS_PACKAGE_VALUE = "io.github.loveeleve.polydata.mybatis.handle";

    @Bean
    public ConfigurationCustomizer configurationCustomizer(MybatisProperties mybatisProperties, DynamicJdbcConfig dynamicJdbcConfig) {

        return new ConfigurationCustomizer() {

            @Override
            public void customize(Configuration configuration) {
                String typeHandlersPackage = mybatisProperties.getTypeHandlersPackage();
                DynamicJdbcConfig.Mybatis mybatis = dynamicJdbcConfig.getMybatis();
                String typeEnumsPackage = mybatis.getBasePackages();
                if (StringUtils.isNotEmpty(typeHandlersPackage) && StringUtils.contains(typeHandlersPackage, MYBATIS_TYPE_HANDLERS_PACKAGE_VALUE)
                        && StringUtils.isNotEmpty(typeEnumsPackage)) {
                    Set<Class> classes;
                    if (typeEnumsPackage.contains("*") && !typeEnumsPackage.contains(",") && !typeEnumsPackage.contains(";")) {
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
            throw new RuntimeException(String.format("not find scanTypePackage: %s", pkg), e);
        }
    }
}
