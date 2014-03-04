package com.mangofactory.swagger.readers;

import com.fasterxml.classmate.ResolvedType;
import com.fasterxml.classmate.TypeResolver;
import com.google.common.base.Optional;
import com.mangofactory.swagger.configuration.SwaggerGlobalSettings;
import com.mangofactory.swagger.core.ModelUtils;
import com.mangofactory.swagger.models.ModelContext;
import com.mangofactory.swagger.models.ModelProvider;
import com.mangofactory.swagger.readers.operation.HandlerMethodResolver;
import com.mangofactory.swagger.readers.operation.ResolvedMethodParameter;
import com.mangofactory.swagger.scanners.RequestMappingContext;
import com.wordnik.swagger.annotations.ApiOperation;
import com.wordnik.swagger.model.Model;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.method.HandlerMethod;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.collect.Maps.*;
import static com.mangofactory.swagger.models.ResolvedTypes.*;

@Component
public class ApiModelReader implements Command<RequestMappingContext> {
    private static final Logger log = LoggerFactory.getLogger(ApiModelReader.class);
    private ModelProvider modelProvider;

    @Autowired
    public ApiModelReader(ModelProvider modelProvider) {
        this.modelProvider = modelProvider;
    }

    @Override
    public void execute(RequestMappingContext context) {
        HandlerMethod handlerMethod = context.getHandlerMethod();

        log.debug("Reading models for handlerMethod |{}|", handlerMethod.getMethod().getName());

        Map<String, Model> modelMap = newHashMap();
        SwaggerGlobalSettings swaggerGlobalSettings = (SwaggerGlobalSettings) context.get("swaggerGlobalSettings");
        HandlerMethodResolver handlerMethodResolver
                = new HandlerMethodResolver(swaggerGlobalSettings.getTypeResolver());
        ResolvedType modelType = ModelUtils.handlerReturnType(swaggerGlobalSettings.getTypeResolver(), handlerMethod);

        ApiOperation apiOperationAnnotation = handlerMethod.getMethodAnnotation(ApiOperation.class);
        if (null != apiOperationAnnotation && Void.class != apiOperationAnnotation.response()) {
            modelType = asResolved(swaggerGlobalSettings.getTypeResolver(), apiOperationAnnotation.response());
        }
        if (!swaggerGlobalSettings.getIgnorableParameterTypes().contains(modelType.getErasedType())) {
            ModelContext modelContext = ModelContext.returnValue(modelType);
            markIgnorablesAsHasSeen(swaggerGlobalSettings.getIgnorableParameterTypes(), modelContext);
            Optional<Model> model = modelProvider.modelFor(modelContext);
            if (model.isPresent() && !"void".equals(model.get().name())) {
                log.debug("Swagger generated parameter model id: {}, name: {}, schema: {} models",
                        model.get().id(),
                        model.get().name());
                modelMap.put(model.get().id(), model.get());
            } else {
                log.debug("Swagger core did not find any models");
            }
            populateDependencies(modelContext, modelMap);
        }
        modelMap.putAll(readParametersApiModel(handlerMethodResolver, swaggerGlobalSettings, handlerMethod));

        log.debug("Finished reading models for handlerMethod |{}|", handlerMethod.getMethod().getName());
        context.put("models", modelMap);
    }

    private void markIgnorablesAsHasSeen(Set<Class> ignorableParameterTypes, ModelContext modelContext) {
        for (Class ignorableParameterType : ignorableParameterTypes) {
            modelContext.seen(asResolved(new TypeResolver(), ignorableParameterType));
        }
    }

    private Map<String, Model> readParametersApiModel(HandlerMethodResolver handlerMethodResolver,
    SwaggerGlobalSettings settings, HandlerMethod handlerMethod) {

        Method method = handlerMethod.getMethod();
        Map<String, Model> modelMap = newHashMap();

        log.debug("Reading parameters models for handlerMethod |{}|", handlerMethod.getMethod().getName());

        List<ResolvedMethodParameter> parameterTypes = handlerMethodResolver.methodParameters(handlerMethod);
        Annotation[][] annotations = method.getParameterAnnotations();

        for (int i = 0; i < annotations.length; i++) {
            Annotation[] pAnnotations = annotations[i];
            for (Annotation annotation : pAnnotations) {
                if (annotation instanceof RequestBody) {
                    ResolvedMethodParameter pType = parameterTypes.get(i);
                    if (!settings.getIgnorableParameterTypes()
                            .contains(pType.getResolvedParameterType().getErasedType())) {
                        ModelContext modelContext = ModelContext.inputParam(pType.getResolvedParameterType());
                        markIgnorablesAsHasSeen(settings.getIgnorableParameterTypes(), modelContext);
                        Optional<Model> pModel = modelProvider.modelFor(modelContext);
                        if (pModel.isPresent()) {
                            log.debug("Swagger generated parameter model id: {}, name: {}, schema: {} models",
                                    pModel.get().id(),
                                    pModel.get().name());
                            modelMap.put(pModel.get().id(), pModel.get());
                        } else {
                            log.debug("Swagger core did not find any parameter models for {}",
                                    pType.getResolvedParameterType());
                        }
                        populateDependencies(modelContext, modelMap);
                    }
                }
            }
        }
        log.debug("Finished reading parameters models for handlerMethod |{}|", handlerMethod.getMethod().getName());
        return modelMap;
    }

    private void populateDependencies(ModelContext modelContext, Map<String, Model> modelMap) {
        modelMap.putAll(modelProvider.dependencies(modelContext));
    }

}
